package com.qiqi.li.living.container;
import com.qiqi.li.living.components.LivingComponents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.util.DoubleChestPositions;
import com.qiqi.li.living.util.FailureThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.RandomizableContainer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 活物品容器处理器 —— 负责遍历容器中的物品并执行活物品 tick。
 *
 * 职责：
 * 1. 将不同类型的容器统一转换为 {@link ContainerContext}
 * 2. 遍历容器中所有物品，对活物品执行已注册功能的 tick 逻辑
 * 3. 处理大箱子的去重（避免左右两半被分别处理导致速度翻倍）
 *
 * 容器类型与 Context 构建策略：
 * - 玩家背包（Inventory）：直接包装为 SimpleContainerContext
 * - 方块容器（箱子等）：通过 IItemHandler 能力获取并包装
 * - 大箱子：NeoForge 为左右两半返回同一个 IItemHandler 实例，天然去重
 */
public class ContainerLivingItemHandler {
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 排查开关：{@code -Dliving_item.debug.isolation=false} 让异常直接抛出（默认隔离）。 */
    private static final boolean ISOLATION_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("living_item.debug.isolation", "true"));

    /**
     * 容器聚合条目：领域数据、修订计数、内容签名与快照共享同一缓存键。
     * 整体回收只走 {@link #removeDataByKey}，过期判据归 {@link #sweepUnseenContainers()}。
     */
    private static final Map<String, ContainerEntry> CONTAINER_DATA = new HashMap<>();

    /**
     * 位置 → 缓存键 反向索引（键空间与上面不同：PosKey→cacheKey，不是 cacheKey→数据）。
     * 供 mixin 热路径（{@code BlockStateBase.getSignal}、{@code RedStoneWireBlock.getConnectingSide}）
     * 以 O(1) 定位容器数据，替代按坐标正则遍历全表。
     */
    private static final Map<PosKey, String> POS_TO_CACHE_KEY = new HashMap<>();

    /**
     * 每容器聚合数据；rev/contentSig/snapshot 跨 tick 持久。
     * 容器级数据（流体 / 红石 / 电力）改由 {@link ContainerDataStore} 统一存储
     * （2026-10-03，1a-4）：新增一种数据只需定义一个 {@link ContainerDataKey} 常量，
     * 本类与 store 零改动。
     */
    private static final class ContainerEntry {
        final ContainerDataStore store = new ContainerDataStore();
        long revision;
        int contentSig = Integer.MIN_VALUE;   // 未算过签名时的哨兵
        long cachedSnapshotRevision = -1;      // 与下方快照配对的修订计数
        ContainerSnapshot cachedSnapshot;
        /**
         * 静息闩锁（Q4a）：「扫描无活物品的完整后缀已成功跑过一拍」。tick 级内存态，不持久化
         * —— entry 销毁（停服 / sweep / 显式删除）即重置，新 entry 首拍必走完整管道
         * （冷附件 / SavedData 的流体等靠这一拍回填）。
         */
        boolean suffixSettled;
    }

    static {
        // static 缓存清理：登记与 clearAllCaches() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        StaticCacheRegistry.onServerStop(s -> ContainerLivingItemHandler.clearAllCaches());
    }

    /**
     * 运行时缓存生命周期追踪（方案 A，2026-10-11）：容器键 → （槽位 → 上次的活物品类型）。
     *
     * <p>用于识别「物品离开槽位 / 被替换 / 取消活化」并作废该槽位的
     * {@link com.qiqi.li.living.runtime.ContainerRuntimeCache} 数据 ——
     * 运行时数据属于「当前槽位里的那台机器」，机器换了，数据就该作废。</p>
     *
     * <p>⚠️ 判据**只看物品类型、不看组件**：燃烧标志等组件会被 tick 改写，
     * 若纳入判据会把正在运行的缓存误清（燃料 / 进度每次状态翻转就丢）。</p>
     */
    private static final Map<String, Map<Integer, Item>> LIVING_SLOT_ITEMS = new ConcurrentHashMap<>();

    /**
     * 缓存键 → 回收起点的墙钟毫秒；正常 tick 盖章，首次创建聚合条目补登记。
     * 普通查询不续期，整容器过期仅由 {@link #sweepUnseenContainers()} 判断。
     *
     * <p>覆盖区块卸载、玩家下线、实体消失、物品销毁、掉落物拾取与非玩家移除方块。
     * 仅查询创建、未进入 tick 的条目按首次创建时刻回收。</p>
     *
     * <p>⚠️ 阈值只保留瞬态缓存的返回窗口；必须跨卸载持久化的数据不得只存内存。</p>
     */
    private static final Map<String, Long> LAST_SEEN = new ConcurrentHashMap<>();

    /** 消失多久算「容器没了」 —— 5 分钟。 */
    static final long UNSEEN_TTL_MS = 5 * 60 * 1000L;

    /** 全表扫描的节拍（表可达上万键，不能每 tick 扫）。 */
    private static final long SWEEP_INTERVAL_MS = 60 * 1000L;

    private static long lastSweepMs;

    /** 槽位作废在扫槽后同步执行，整容器作废由 L4 在拍末消费。 */
    private static BiConsumer<String, Set<Integer>> runtimeSlotInvalidator = (key, slots) -> {};
    private static final List<RuntimeCacheInvalidation> PENDING_RUNTIME_INVALIDATIONS = new ArrayList<>();

    /** 整容器作废；键与运行时缓存同口径。 */
    public record RuntimeCacheInvalidation(String cacheKey) {}

    /** common setup 接线；回调必须立即作废给定物理槽，不能延后到设备执行之后。 */
    public static void setRuntimeSlotInvalidator(BiConsumer<String, Set<Integer>> invalidator) {
        runtimeSlotInvalidator = java.util.Objects.requireNonNull(invalidator);
    }

    /** 维度 + 坐标，作为位置索引的键（含维度，避免跨维度同坐标冲突） */
    private record PosKey(ResourceKey<Level> dim, BlockPos pos) {}

    /**
     * 构建容器数据的缓存键。
     *
     * <p>⚠️ 2026-10-12 起<b>只是薄委托</b>：唯一定义点在
     * {@link ContainerIdentity#getCacheKey()}（世界容器 = {@code <维度>|<稳定键>}，
     * 玩家 / 掉落物 = 裸键）。本类原先各写一份前缀逻辑，导致 {@code CONTAINER_DATA}
     * 带维度而运行时缓存 / 槽位追踪不带 ⇒ 跨维度同坐标容器串数据。</p>
     *
     * @return 缓存键，容器无 containerKey 时返回 null
     */
    private static String cacheKey(ContainerContext ctx) {
        return ctx.getCacheKey();
    }

    /** 登记方块宿主的所有关联位置；实体位置快照不参与反查。 */
    private static void indexPositions(ContainerContext ctx, String cacheKey) {
        if (ctx.getBlockPos() == null) return;
        Level level = ctx.getLevel();
        if (level == null) return;
        ResourceKey<Level> dim = level.dimension();
        if (dim == null) return; // 无维度信息（如单元测试 mock）时跳过位置索引
        for (BlockPos pos : ctx.getAssociatedBlockPositions()) {
            POS_TO_CACHE_KEY.put(new PosKey(dim, pos.immutable()), cacheKey);
        }
    }

    /**
     * 获取或创建容器聚合条目（含惰性创建流体/红石/电力数据）。
     * 首次创建时登记回收起点与位置反向索引，后续查询不续期；无 containerKey 时返回 null。
     */
    private static ContainerEntry entry(ContainerContext ctx) {
        String key = cacheKey(ctx);
        if (key == null) return null;
        ContainerEntry e = CONTAINER_DATA.get(key);
        if (e == null) {
            e = new ContainerEntry();
            CONTAINER_DATA.put(key, e);
            LAST_SEEN.putIfAbsent(key, System.currentTimeMillis());
            indexPositions(ctx, key);
        }
        return e;
    }

    /** 非创建读取聚合条目（区别于 {@link #entry}）；供静息闩锁判断，不登记回收起点。 */
    private static ContainerEntry existingEntry(ContainerContext ctx) {
        String key = cacheKey(ctx);
        return key == null ? null : CONTAINER_DATA.get(key);
    }

    /**
     * 是否有自维持候选声明「现在仍有持续的无物品工作」（两阶段语义，ctx != null 查询）。
     * 只遍历注册期静态清单；第三方候选若恒返回 true，只会保守地阻止静息跳过，不漏 tick。
     */
    private static boolean hasContinuousSelfSustainingWork(ContainerContext ctx) {
        for (var f : LivingItemManager.getSelfSustainingFunctions()) {
            if (f.shouldTickWithoutOwnItems(ctx)) return true;
        }
        return false;
    }

    /**
     * 清理指定位置容器的流体/红石/电力数据（容器方块被破坏时调用）。
     */
    public static void removeDataByPos(Level level, BlockPos pos) {
        String cacheKey = POS_TO_CACHE_KEY.get(new PosKey(level.dimension(), pos.immutable()));
        if (cacheKey == null) return;
        removeDataByKey(cacheKey);
    }

    /**
     * 按<b>缓存键</b>作废一个容器的全部跨 tick 数据（物品形态容器的「容器消失」出口）。
     *
     * <p>⚠️ 物品形态容器（{@code cuuid_}）<b>没有位置</b> ⇒ {@link #removeDataByPos} 的反查
     * 索引永远命中不了它。取消活化是它唯一的显式消失点（{@code ContainerUuids.discard}），
     * 必须在这里按键作废，否则运行时缓存（熔炉进度 / 燃料）与槽位追踪会留到停服
     * （{@code ContainerRuntimeCache} 没有 TTL）。</p>
     */
    public static void removeDataByKey(String cacheKey) {
        if (cacheKey == null) return;
        CONTAINER_DATA.remove(cacheKey);
        LAST_SEEN.remove(cacheKey);
        POS_TO_CACHE_KEY.values().removeIf(cacheKey::equals);
        // 容器卸载 ⇒ 运行时缓存与追踪一并作废（方案 A，2026-10-11）。
        // ⚠️ key 与下方两行必须是<b>同一把缓存键</b>（ContainerIdentity.getCacheKey）：
        // 2026-10-12 修复前这里用 POS 索引取出的「维度前缀键」去删 LIVING_SLOT_ITEMS /
        // 登记作废，而 trackLivingSlots 与 ContainerRuntimeCache 用的是裸容器键
        // ⇒ 两条 remove 全部落空，「拆箱子 ⇒ 运行时缓存作废」从未真正生效
        // （拆掉原地放回 ⇒ 旧燃料/进度复活）。
        LIVING_SLOT_ITEMS.remove(cacheKey);
        PENDING_RUNTIME_INVALIDATIONS.add(new RuntimeCacheInvalidation(cacheKey));
    }

    // ==================== 运行时缓存生命周期（方案 A，2026-10-11） ====================

    /**
     * 对比「本 tick 的活物品槽位」与「上次记录」，把<b>已失效</b>的槽位登记进待清队列。
     *
     * <p>失效判据（任一成立）：① 该槽位本 tick 不再是活物品（拿走 / 换成普通物品 / 取消活化）；
     * ② 该槽位换成了<b>另一种</b>活物品（{@code Item} 变了）。</p>
     *
     * <p>⚠️ {@code cacheKey} 必须是 {@link ContainerIdentity#getCacheKey()}（带维度前缀的
     * 缓存键）—— 2026-10-12 前这里收裸 {@code getContainerKey()}，而 {@link #removeDataByPos}
     * 从 {@code POS_TO_CACHE_KEY} 取出的是带前缀的缓存键 ⇒ 两边永远对不上，
     * 「容器整体卸载 ⇒ 作废整个运行时缓存」整条路径<b>登记即打空</b>。</p>
     *
     * <p>⚠️ 已知边界：<b>同类物品原地交换</b>（手持新熔炉直接点击旧熔炉）判据看不出变化 ⇒ 不清。
     * 要彻底解决需给物品加实例 id（会引入堆叠 / 拆堆语义问题）⇒ 当前接受。</p>
     *
     * <p>本方法同时是<b>「本 tick 还看到该容器」的盖章点</b>（{@link #LAST_SEEN}）：
     * 它对每个进入 {@code processContext} 的容器都会调用（含活物品已清空的容器 ——
     * 「没有活物品」不等于「消失了」），所以盖章必须在下面任何早退/清理分支<b>之前</b>。
     * 回收侧见 {@link #sweepUnseenContainers()}。</p>
     *
     * @param ctx 容器上下文，仅用于首次出现时补位置反向索引；null（测试替身 / 无世界）时跳过索引
     */
    static Set<Integer> trackLivingSlots(String cacheKey, Map<Integer, Item> currentSlots,
                                         @javax.annotation.Nullable ContainerContext ctx) {
        if (cacheKey == null) return Set.of();
        LAST_SEEN.put(cacheKey, System.currentTimeMillis());
        Map<Integer, Item> previous = LIVING_SLOT_ITEMS.get(cacheKey);
        Set<Integer> stale = null;
        if (previous != null) {
            for (Map.Entry<Integer, Item> e : previous.entrySet()) {
                if (currentSlots.get(e.getKey()) != e.getValue()) {
                    if (stale == null) stale = new HashSet<>();
                    stale.add(e.getKey());
                }
            }
        }
        if (currentSlots.isEmpty()) {
            LIVING_SLOT_ITEMS.remove(cacheKey);
        } else {
            LIVING_SLOT_ITEMS.put(cacheKey, new LinkedHashMap<>(currentSlots));
            // R-06 缺口 1（2026-11-01）：位置索引原先只在 entry(ctx) 创建容器级数据时建 ⇒
            // 只装活物品、没有流体/红石/电力数据的容器<b>永远没有索引</b>，玩家破坏也清不掉。
            // 这里在「追踪条目首次出现」时补建：稳态零成本（不每 tick 重算位置），
            // 而区块重新加载 / 清空后重新装入活物品都会再走一次 ⇒ 位置不会长期陈旧。
            if (previous == null && ctx != null) {
                indexPositions(ctx, cacheKey);
            }
        }
        return stale == null ? Set.of() : stale;
    }

    /**
     * 回收「不再被 tick 看到」的容器 —— L4 每 tick 调，内部按 {@link #SWEEP_INTERVAL_MS} 门控。
     *
     * <p>覆盖：区块卸载、玩家下线、实体消失 / 死亡、物品被销毁或丢失、掉落物被拾取、
     * 爆炸 / 活塞 / {@code /setblock} 等非玩家移除。这六种成因都不再有显式事件可依，
     * 但都会表现为「这个缓存键没再出现在 {@link #LAST_SEEN} 的更新里」。</p>
     *
     * <p>语义按 {@code D-container-20}：运行时数据瞬态可丢 ⇒ 这里走
     * {@link #removeDataByKey}（与「玩家破坏方块」同一条作废入口，四张表 + 整容器运行时作废），
     * 不新增第二套清理路径。</p>
     *
     * @return 本次回收的容器数（诊断 / 测试用）
     */
    public static int sweepUnseenContainers() {
        long now = System.currentTimeMillis();
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) return 0;
        lastSweepMs = now;
        return sweepUnseenContainers(now, UNSEEN_TTL_MS);
    }

    /** 实际回收体（无门控，时刻与阈值可注入 —— 测试不必等 5 分钟）。 */
    static int sweepUnseenContainers(long nowMs, long ttlMs) {
        int removed = 0;
        List<String> expired = null;
        for (var en : LAST_SEEN.entrySet()) {
            if (nowMs - en.getValue() >= ttlMs) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(en.getKey());
            }
        }
        if (expired != null) {
            for (String cacheKey : expired) {
                removeDataByKey(cacheKey);
                removed++;
            }
        }
        // 物品形态的活视图是同一族缺陷（只在 find() 里惰性过期），并入同一节拍主动淘汰
        ItemFormLiveViews.evictExpired(nowMs);
        return removed;
    }

    /** 测试接缝：把某键的「最后被看到」时刻改到指定值，模拟容器停止 tick。 */
    static void setLastSeenForTest(String cacheKey, long atMs) {
        LAST_SEEN.put(cacheKey, atMs);
    }

    /** 当前被追踪（含活物品）的容器数 —— 诊断与测试用，不进入热路径。 */
    public static int trackedContainerCount() {
        return LIVING_SLOT_ITEMS.size();
    }

    /**
     * 跨 tick 表的规模 —— 给 {@code /living_monitor cache} 打印，用来验证 sweep 真在回收：
     * 玩家走远一圈再回来，{@code 盖章键} 应回落到稳态而不是单调上涨
     * （整改计划 §6.3 实机第 3 条）。
     */
    public static String describeLifecycleStats() {
        return String.format("追踪容器=%d 盖章键=%d 位置索引=%d 待作废=%d",
            LIVING_SLOT_ITEMS.size(), LAST_SEEN.size(),
            POS_TO_CACHE_KEY.size(), PENDING_RUNTIME_INVALIDATIONS.size());
    }

    /**
     * 单容器只读体检（{@code /livingitem debug container}，2026-11-21）—— 诊断「箱子为什么不 tick」用。
     *
     * <p>输出四层状态：身份键 / 聚合条目（含静息闩锁与容器级数据键）/ 盖章年龄 / 活物品槽位追踪，
     * 末尾逐关联位置报「位置反查」指向 —— 索引不指回本键是发现缓存同族回归的直接签名
     * （mixin 热路径按位置 O(1) 查容器级数据，查不到即红石等世界互通失灵）。</p>
     *
     * <p>⚠️ 全程只读：不建聚合条目、不盖章、不建位置索引、不回填容器级数据 ——
     * 诊断本身不得改变被诊断的状态（对比 {@code getOrCreateContainerData} 会回填）。</p>
     */
    public static String diagnoseContainer(ContainerContext ctx) {
        String cacheKey = cacheKey(ctx);
        if (cacheKey == null) {
            return "containerKey=null（上下文无键，无法关联任何跨 tick 表）";
        }
        StringBuilder sb = new StringBuilder("key=").append(cacheKey);
        java.util.UUID uuid = ctx.getContainerUuid();
        if (uuid != null) sb.append(" uuid=").append(uuid);

        ContainerEntry e = CONTAINER_DATA.get(cacheKey);
        sb.append(" | 聚合条目=").append(e == null ? "无（不在容器管道，或已被 sweep / 显式删除）" : "有");
        if (e != null) {
            sb.append(" 静息闩锁=").append(e.suffixSettled ? "已结算（无活物品时走快路径）" : "未结算（完整管道）");
            sb.append(" 修订=").append(e.revision);
            sb.append(" 容器级数据=[");
            boolean anyData = false;
            for (ContainerDataKey<?> key : ContainerDataKey.all()) {
                if (e.store.peek(key) != null) {
                    if (anyData) sb.append(", ");
                    sb.append(key.id());
                    anyData = true;
                }
            }
            if (!anyData) sb.append("无");
            sb.append(']');
        }

        Long lastSeen = LAST_SEEN.get(cacheKey);
        if (lastSeen == null) {
            sb.append(" | 盖章=无记录（从未进入 tick，或已被回收）");
        } else {
            long ageMs = System.currentTimeMillis() - lastSeen;
            sb.append(" | 盖章距今=").append(String.format("%.1f", ageMs / 1000.0)).append("s");
            if (ageMs >= UNSEEN_TTL_MS) {
                sb.append("（已超 TTL，sweep 将回收）");
            }
        }

        Map<Integer, Item> slots = LIVING_SLOT_ITEMS.get(cacheKey);
        if (slots == null) {
            sb.append(" | 活物品槽位追踪=无");
        } else {
            sb.append(" | 活物品槽位追踪=[");
            boolean anySlot = false;
            for (var en : slots.entrySet()) {
                if (anySlot) sb.append(", ");
                sb.append(en.getKey()).append('=').append(en.getValue());
                anySlot = true;
            }
            if (!anySlot) sb.append("空");
            sb.append(']');
        }

        ResourceKey<Level> dim = ctx.getLevel() == null ? null : ctx.getLevel().dimension();
        if (dim != null) {
            for (BlockPos pos : ctx.getAssociatedBlockPositions()) {
                String indexed = POS_TO_CACHE_KEY.get(new PosKey(dim, pos.immutable()));
                sb.append(" | 位置反查 ").append(pos.toShortString()).append('=');
                if (indexed == null) {
                    sb.append("无索引（mixin 热路径查不到该容器）");
                } else {
                    sb.append(indexed.equals(cacheKey) ? "指向本键" : "指向其他(" + indexed + ")");
                }
            }
        }
        return sb.toString();
    }

    /** 从扫描结果收集「本 tick 的活物品槽位 → 物品类型」（同槽位多功能 ⇒ 去重）。 */
    private static Map<Integer, Item> collectLivingSlots(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            ContainerContext ctx) {
        Map<Integer, Item> slots = new LinkedHashMap<>();
        for (var list : grouped.values()) {
            for (var slotEntry : list) {
                ItemStack st = slotEntry.stack();
                if (!st.isEmpty()) {
                    // 键 = 物理槽（与 ContainerRuntimeCache.update 同口径）
                    slots.put(ctx.toPhysicalSlot(slotEntry.slotIndex()), st.getItem());
                }
            }
        }
        return slots;
    }

    /**
     * 取走本 tick 攒下的「待清运行时缓存」项（取走即清空）——
     * 由 L4 消费并调用 {@code ContainerRuntimeCache}（保持 L1 不依赖 L2）。
     */
    public static List<RuntimeCacheInvalidation> drainRuntimeCacheInvalidations() {
        if (PENDING_RUNTIME_INVALIDATIONS.isEmpty()) return List.of();
        List<RuntimeCacheInvalidation> out = new ArrayList<>(PENDING_RUNTIME_INVALIDATIONS);
        PENDING_RUNTIME_INVALIDATIONS.clear();
        return out;
    }

    /** 按位置 O(1) 查询容器级数据（泛化；供 mixin 热路径与领域侧调用）。 */
    public static <T> T peekContainerDataByPos(Level level, BlockPos pos, ContainerDataKey<T> key) {
        String cacheKey = POS_TO_CACHE_KEY.get(new PosKey(level.dimension(), pos));
        if (cacheKey == null) return null;
        ContainerEntry e = CONTAINER_DATA.get(cacheKey);
        return e == null ? null : e.store.peek(key);
    }

    /**
     * 写入容器级数据（按 key）—— 供领域做「附件回填」等需要替换实例的场景。
     */
    public static <T> void putContainerData(ContainerContext ctx, ContainerDataKey<T> key, T value) {
        ContainerEntry e = entry(ctx);
        if (e != null) e.store.put(key, value);
    }

    /**
     * 移除容器级数据（按 key；不存在则忽略）—— 供领域写回钩子在「数据已清空」时回收 store 槽。
     */
    public static <T> void removeContainerData(ContainerContext ctx, ContainerDataKey<T> key) {
        String cacheKey = cacheKey(ctx);
        if (cacheKey == null) return;
        ContainerEntry e = CONTAINER_DATA.get(cacheKey);
        if (e != null) e.store.put(key, null);
    }

    /**
     * 泛型访问容器级持久数据（不创建子数据；首次查询仍登记聚合条目）。供 {@code SimpleContainerContext} 的
     * {@code peekContainerData} 覆写委托 —— 按 key 走统一 store，无需按类型 switch。
     */
    public static <T> T peekContainerData(ContainerContext ctx, ContainerDataKey<T> key) {
        ContainerEntry e = entry(ctx);
        return e == null ? null : e.store.peek(key);
    }

    /** 非创建热表查询；不建立聚合条目、位置索引或回收计时。 */
    public static <T> T peekExistingContainerData(ContainerContext ctx, ContainerDataKey<T> key) {
        String cacheKey = cacheKey(ctx);
        ContainerEntry e = cacheKey == null ? null : CONTAINER_DATA.get(cacheKey);
        return e == null ? null : e.store.peek(key);
    }

    /**
     * 泛型访问或创建容器级持久数据。见 {@link #peekContainerData(ContainerContext, ContainerDataKey)}。
     *
     * <p><b>回填的唯一入口</b>（2026-10-29 收编）：内存 store 命中 ⇒ 直接返回；否则按
     * 「有身份 → SavedData / 无身份 → 宿主附件」取回，最后才新建。</p>
     *
     * <p>⚠️ 为什么回填必须在这里：`persist` 已经二分（有身份只写 SavedData）⇒ 各领域自己写一份
     * 回填等于「同一个问题两份答案」，而且漏写的领域会表现为「写了存档但永远读不回来」
     * （红石账本 / 红电相位就是这样：它们从来只有内存，没有回填）。</p>
     */
    public static <T> T getOrCreateContainerData(ContainerContext ctx, ContainerDataKey<T> key) {
        ContainerEntry e = entry(ctx);
        if (e == null) return key.create();

        T existing = e.store.peek(key);
        if (existing != null) return existing;

        // ① 有身份：SavedData 是真身（跨形态稳定，方块↔物品切换零搬运）
        java.util.UUID uuid = ctx.getContainerUuid();
        if (uuid != null && key.isSaved()) {
            T saved = ContainerDataSavedData.get().get(uuid, key);
            if (saved != null && !key.isEmpty(saved)) {
                e.store.put(key, saved);
                return saved;
            }
        }

        // ② 无身份：宿主附件是唯一副本（原版箱子 / 实体容器）
        net.neoforged.neoforge.attachment.AttachmentType<T> att = key.attachment();
        if (att != null) {
            for (var host : ctx.getDataHosts()) {
                T persisted = host.getData(att);
                if (persisted != null && !key.isEmpty(persisted)) {
                    e.store.put(key, persisted);
                    return persisted;
                }
            }
        }

        return e.store.getOrCreate(key);
    }

    /**
     * 读取容器当前修订计数（物品内容版本号）。无 containerKey 的上下文返回 0。
     */
    public static long getContainerRevision(ContainerContext ctx) {
        String key = cacheKey(ctx);
        if (key == null) return 0L;
        ContainerEntry e = CONTAINER_DATA.get(key);
        return e == null ? 0L : e.revision;
    }

    /**
     * 自增容器修订计数。物品被 {@code setItem} 替换、或经 {@code syncSlotToClients}
     * 就地修改 DataComponent（方向配置、状态等）后调用，使快照缓存失效。
     */
    public static void bumpContainerRevision(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return;
        e.revision++;
    }

    /**
     * 用内容签名兜底容器修订计数：内容变化则 bump，使稳态跳过与快照缓存失效。
     *
     * <p>必须在每 tick 的槽位扫描之后、{@code TickContext} 创建之前调用，
     * 这样本 tick 就能看到最新的物品与重算结果。开销 O(槽位数)。</p>
     *
     * <p>签名含「物品 id + 全部组件 + 数量」（Q2）：原版 GUI 的同 ID 同数量换组件
     * （如交换两个不同过滤链的活漏斗）不经 {@code setItem}/{@code syncSlotToClients}，
     * 只有签名兜底能检出。⚠️ 签名只在内存比对、<b>不得持久化</b> —— 组件类型索引是
     * identityHashCode，跨会话不稳定。</p>
     */
    public static void syncContentRevision(ContainerContext ctx) {
        syncContentRevision(ctx, computeContentSignature(ctx));
    }

    /** 带 预计算签名 的兜底入口（Q2：签名与槽位扫描合一后由 processContext 传入）。 */
    static void syncContentRevision(ContainerContext ctx, int signature) {
        ContainerEntry e = entry(ctx);
        if (e == null) return;
        int prev = e.contentSig;
        e.contentSig = signature;
        if (prev == Integer.MIN_VALUE || prev != signature) {
            bumpContainerRevision(ctx);
        }
    }

    /** 容器内容签名：逐槽位混入 物品 id、全部组件与数量（空槽参与混合，保证槽位移动也能检出） */
    private static int computeContentSignature(ContainerContext ctx) {
        int h = 1;
        int size = ctx.getSize();
        for (int i = 0; i < size; i++) {
            h = mixSlot(h, ctx.getItem(i));
        }
        return h;
    }

    /**
     * 单槽混入：{@link ItemStack#hashItemAndComponents} 含物品 id 与全部组件但<b>不含数量</b>
     * ⇒ 数量必须在此单独混入（漏 count = 数量变化漏检）。
     */
    private static int mixSlot(int h, ItemStack s) {
        if (s == null || s.isEmpty()) {
            return h * 31;
        }
        return h * 31 + ItemStack.hashItemAndComponents(s) * 31 + s.getCount();
    }

    /**
     * 取（或构建并缓存）容器快照。仅当修订计数相对上次构建发生变化时才重建，
     * 否则复用跨 tick 缓存的同一快照，避免每个 tick 重复扫描全部物品。
     */
    public static ContainerSnapshot getCachedSnapshot(ContainerContext ctx, long revision,
                                                      TickContext tick) {
        ContainerEntry e = entry(ctx);
        if (e == null) {
            com.qiqi.li.living.perf.PerfMetrics.recordSnapshotRebuild();
            return ContainerSnapshot.capture(ctx, tick);
        }
        if (e.cachedSnapshot != null && e.cachedSnapshotRevision == revision) {
            com.qiqi.li.living.perf.PerfMetrics.recordSnapshotHit();
            return e.cachedSnapshot;
        }
        com.qiqi.li.living.perf.PerfMetrics.recordSnapshotRebuild();
        ContainerSnapshot snap = ContainerSnapshot.capture(ctx, tick);
        e.cachedSnapshotRevision = revision;
        e.cachedSnapshot = snap;
        return snap;
    }

    /** 清空全部容器级缓存（服务端关闭时调用，避免跨存档残留） */
    public static void clearAllCaches() {
        CONTAINER_DATA.clear();
        POS_TO_CACHE_KEY.clear();
        LIVING_SLOT_ITEMS.clear();
        PENDING_RUNTIME_INVALIDATIONS.clear();
        LAST_SEEN.clear();
        lastSweepMs = 0;
    }


    /**
     * 处理玩家背包中的所有活物品。
     *
     * @param inventory 玩家背包
     * @param level 世界
     */
    public static void processContainer(Inventory inventory, Level level) {
        if (level.isClientSide) return;
        IItemHandler handler = inventory.player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (handler == null) return;
        TickableContainerContext context = buildContext(handler, inventory, level);
        processIsolated(context, level);

        processEnderChest(inventory.player, level);
    }

    /**
     * 处理玩家末影箱中的所有活物品。
     *
     * <p>末影箱容器（{@link PlayerEnderChestContainer}）既不是方块实体也不是玩家背包的一部分，
     * 需要单独处理。使用 "player_&lt;uuid&gt;_ender_chest" 作为容器 key，
     * 与玩家背包的 key（"player_&lt;uuid&gt;"）区分。</p>
     *
     * @param player 玩家
     * @param level 世界
     */
    public static void processEnderChest(Player player, Level level) {
        if (level.isClientSide) return;
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        if (enderChest == null) return;

        IItemHandler handler = new InvWrapper(enderChest);
        TickableContainerContext context = new EnderChestContainerContext(handler, player, level);
        processIsolated(context, level);
    }

    /**
     * 根据容器类型构建对应的 ContainerContext。
     *
     * @param handler IItemHandler
     * @param inventory 玩家背包（nullable，仅玩家背包场景传入）
     * @param level 世界
     * @return 包装后的容器上下文
     */
    public static TickableContainerContext buildContext(IItemHandler handler, Inventory inventory, Level level) {
        return new SimpleContainerContext(handler, inventory, new ArrayList<>(), new ArrayList<>(), level);
    }

    /**
     * 隔离执行单个容器上下文：{@link RuntimeException} 记一次并继续，{@link Error} 照常抛出。
     *
     * <p>边界是「一个容器」—— 同区块的其它容器、同一 tick 的收尾逻辑（同步 / 爆炸账本 /
     * 末影 flush）都不受牵连。排查期可用 {@code -Dliving_item.debug.isolation=false}
     * 关闭隔离，让异常直接暴露。</p>
     */
    public static void processIsolated(TickableContainerContext context, Level level) {
        if (!ISOLATION_ENABLED) {
            processContext(context, level);
            return;
        }
        try {
            processContext(context, level);
        } catch (RuntimeException e) {
            FailureThrottle.report(contextKey(context), e);
        }
    }

    /** 隔离层的日志键：容器键 → 实体 UUID → 类型名；取键失败本身不能再炸 tick。 */
    private static String contextKey(TickableContainerContext context) {
        try {
            String key = context.getContainerKey();
            if (key != null && !key.isBlank()) return key;
            Entity entity = context.getEntity();
            if (entity != null) return "entity_" + entity.getUUID();
            return "context_" + context.getClass().getSimpleName();
        } catch (RuntimeException ignored) {
            // 身份读取失败：退化为类型名，日志仍能指出是哪一类容器
            return "context_" + context.getClass().getSimpleName();
        }
    }

    /**
     * 遍历容器中所有物品，按功能分组后统一调用 tick。
     *
     * 两阶段设计：
     * 1. 扫描阶段：遍历容器中所有物品，将活物品按功能类型分组收集
     * 2. 执行阶段：对每种功能只调用一次 tick，传入该容器中所有拥有此功能的活物品列表
     *
     * 为什么按功能分组调用而非逐个调用：
     * 如果逐个调用 tick，每个活物品独立推进自己的状态，
     * 导致总速度随活物品数量线性增长（N 个活熔炉 = N 倍速度）。
     * 按功能分组后，由功能实现自行决定如何分配处理
     * （如活熔炉每 tick 只处理一个），从根本上避免速度翻倍。
     *
     * @param context 可 tick 的容器上下文（掉落物形态也实现 {@link TickableContainerContext}，
     *                靠空实现 / 空列表 / null 表达「无容器级生命周期」）
     * @param level 世界
     */
    public static void processContext(TickableContainerContext context, Level level) {
        long startNanos = System.nanoTime();

        int containerSize = context.getSize();
        if (containerSize <= 0) {
            return;
        }

        // monitorKey 用<b>裸稳定键</b>（getContainerKey）而非缓存键 —— 调试开关
        // （/living monitor enable chest_12_64_34）按玩家看得懂的坐标键下达，
        // 与缓存表内部用的「维度|坐标」不同名是故意的。
        String monitorKey = context.getContainerKey();
        com.qiqi.li.living.debug.ContainerMonitor.beforeProcess(monitorKey, context);

        // 阶段 1：扫描容器全部槽位，将活物品按功能分组，同遍混入内容签名（Q2：两遍全槽降为一遍）
        ScanResult scan = scanAndGroupLivingItems(context);
        syncContentRevision(context, scan.contentSig());
        Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = scan.grouped();

        // ★ 运行时缓存生命周期（方案 A，2026-10-11）：物品离开槽位 / 被替换 ⇒ 作废该槽位的运行时数据。
        // ⚠️ 必须在下方早退分支之前 —— 「容器里已无活物品」正是最需要清理的场景。
        // 键用 getCacheKey()（世界容器带维度前缀）⇒ 与 ContainerRuntimeCache 的存储键、
        // removeDataByPos 的作废键三方同构（2026-10-12 修三处键不一致）。
        // ⚠️ 作废键必须与 ContainerRuntimeCache 的存储键同口径 = 【物理槽】
        // （update 内部过 ctx.toPhysicalSlot；这里同样过一遍，否则作废打空）
        String cacheKey = context.getCacheKey();
        Set<Integer> staleSlots = trackLivingSlots(cacheKey, collectLivingSlots(grouped, context), context);
        if (!staleSlots.isEmpty()) {
            runtimeSlotInvalidator.accept(cacheKey, staleSlots);
        }

        // 静息快路径（Q4a）：扫描未见活物品 + 闩锁已结算 + 无自维持候选报告持续工作
        // ⇒ 跳过后缀（TickContext / 开始钩子 / 自维持注入 / 容器级数据 / 写回 / 脏槽收尾）。
        // 闩锁语义：进入完整管道即清零（防陈旧 true 跳过「物品离场后的清理拍」）；
        // 整拍成功（catch 会 rethrow，走到 try/finally 之后即成功）且本拍扫描无活物品、
        // 无持续工作 ⇒ 置真。冷数据（附件 / SavedData 流体）靠新 entry 首拍必完整来回填。
        // ⚠️ 判据用 scan.hasAnyLivingItem()（扫描见到的 IS_LIVING），不能用 grouped.isEmpty()
        // —— 活黑曜石等「有标记、无认领」物品不进分组，但静息判定必须看见它们。
        ContainerEntry idleEntry = existingEntry(context);
        boolean hasLivingItems = scan.hasAnyLivingItem();
        boolean continuousWork = !hasLivingItems && hasContinuousSelfSustainingWork(context);
        if (!hasLivingItems && !continuousWork
                && idleEntry != null && idleEntry.suffixSettled) {
            long skipEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("scan", skipEndNanos - startNanos);
            PerfMetrics.recordTick(skipEndNanos - startNanos);
            PerfMetrics.recordIdleSuffixSkip();
            com.qiqi.li.living.debug.ContainerMonitor.afterProcess(monitorKey, context);
            if (PerfMetrics.shouldReport()) {
                PerfMetrics.printReport();
            }
            return;
        }
        if (idleEntry != null) {
            idleEntry.suffixSettled = false;
        }

        // 自维持功能（如活水源）：即便容器内没有它自己的活物品，也要进入主流程参与 tick。
        // 遍历的是注册期算好的静态清单（当前 3 个：流体 / 红石 / 门面），不逐函数判定，
        // 保证成千上万个容器下的每 tick 开销可控（详见 LivingItemFunction#shouldTickWithoutOwnItems）。
        for (var f : LivingItemManager.getSelfSustainingFunctions()) {
            grouped.computeIfAbsent(f, k -> new ArrayList<>());
        }

        // 空容器早退 —— ⚠️ **理论不可达**：自维持函数（流体 / 红石领域的驱动函数）
        // 使 grouped 恒非空。保留本分支仅作防御。
        // 2026-10-08：原 handleEmptyContainer（「空容器时让残留红石归零」）已删 ——
        // 归零职责由红石领域的自维持驱动守卫接管（它有 REDSTONE 数据就会跑 calculate，
        // 内部 hasAny=false 分支自然归零）。见 docs/buffer/redstone-driver-consolidation-plan.md §5 改动 4/5。
        if (grouped.isEmpty()) {
            return;
        }

        // 准备 TickContext（功能 tick 与环境交互的上下文）
        TickContext tick = new TickContext(context);
        Throwable failure = null;
        long stressEndNanos;
        try {
            context.setTickContext(tick);
            long scanEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("scan", scanEndNanos - startNanos);

            // 阶段 2：功能 tick（按扫描分组顺序，非优先级顺序）
            tickFunctionSlots(grouped, tick);
            runFunctionTicks(grouped, context, tick, level);
            tick.refreshMovedFunctionSlots(context);

            long funcTickEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("func_tick", funcTickEndNanos - scanEndNanos);

            // 阶段 3：容器级数据（流体 → 水车 → 红石 → 电力，按 prio 排序传播）
            // ⚠️ 原「EnderChannel 脏通道刷新」阶段已移出（2026-10-08 计划 ⑤）——
            // 它是全局幂等动作，改为在 L4 `LivingItem.onServerTick` 每 tick 收口，
            // 不再每容器重复调用（见 docs/buffer/container-domain-decoupling-plan.md）。
            // 2026-10-08：原 zeroResidualRedstone（1b-2c 的「残留红石归零」兜底）已删 ——
            // 红石领域改为自维持后恒在 grouped 里，该兜底的守卫条件恒真、方法体永不执行；
            // 归零由它的驱动守卫接管（有 REDSTONE 数据 ⇒ 跑 calculate ⇒ hasAny=false 分支归零）。
            runContainerDataTicks(grouped, context, tick);

            long containerDataEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("container_data", containerDataEndNanos - funcTickEndNanos);

            // 阶段 4：写回（应力 / 流体 / 相位快照 —— 由各领域注册的 ContainerTickHook 完成）
            ContainerTickHooks.fireWriteback(context, tick);

            stressEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("stress", stressEndNanos - containerDataEndNanos);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            // 开始钩子失败也刷新脏槽；刷新失败仍须解绑。Error 不得被异常合并降为可隔离故障。
            try {
                context.flushDirtySlots();
            } catch (RuntimeException | Error e) {
                if (failure == null) throw e;
                if (e instanceof Error && !(failure instanceof Error)) {
                    e.addSuppressed(failure);
                    throw e;
                }
                if (e != failure) failure.addSuppressed(e);
            } finally {
                context.setTickContext(null);
            }
        }

        // 阶段 6：脏槽刷新（from finally）后的 perf 收尾
        long flushSlotsEndNanos = System.nanoTime();
        PerfMetrics.recordPhase("flush_slots", flushSlotsEndNanos - stressEndNanos);

        // 静息闩锁置真（Q4a）：只有无异常走到这里才算「无活物品后缀成功收口」。
        // catch 分支会 rethrow ⇒ 异常拍永远到不了这一行，下拍继续完整清理。
        if (!hasLivingItems && !continuousWork && idleEntry != null) {
            idleEntry.suffixSettled = true;
        }

        PerfMetrics.recordTick(flushSlotsEndNanos - startNanos);

        com.qiqi.li.living.debug.ContainerMonitor.afterProcess(monitorKey, context);

        if (PerfMetrics.shouldReport()) {
            PerfMetrics.printReport();
        }
    }

    /**
     * 将扫描结果的功能槽位集合写入 TickContext，并记录 PerfMetrics 统计。
     */
    private static void tickFunctionSlots(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            TickContext tick) {
        Map<String, Set<Integer>> functionSlots = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            Set<Integer> slots = new HashSet<>();
            for (var slotEntry : entry.getValue()) {
                slots.add(slotEntry.slotIndex());
            }
            functionSlots.put(entry.getKey().getFunctionId(), slots);
        }
        tick.setFunctionSlots(functionSlots);

        for (var entry : grouped.entrySet()) {
            PerfMetrics.addLivingItem(entry.getKey().getFunctionId(), entry.getValue().size());
            PerfMetrics.recordFunctionCall(entry.getKey().getFunctionId());
        }
    }

    /**
     * 按扫描分组顺序执行各功能的 tick，每个功能只调用一次，传入其管理的所有活物品槽位。
     *
     * <p>⚠️ 顺序 = 功能在槽位扫描中首次出现的先后，不是优先级顺序；
     * 容器级数据才按 {@link HasContainerData#getPriority()} 排序。见 api-contract §1.3。</p>
     */
    private static void runFunctionTicks(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            ContainerContext context, TickContext tick, Level level) {
        // 掉落物形态：只跑**声明支持**的功能（LivingItemFunction#supportsDroppedItem）。
        // 准入是按物品判的（有一个功能支持就建上下文），但同一物品可能挂着多个功能 ——
        // 没声明支持的那些在这里被挡住，否则「声明了才准入」就成了空话。
        boolean droppedItem = context instanceof ItemEntityContainerContext;
        for (var entry : grouped.entrySet()) {
            if (droppedItem && !entry.getKey().supportsDroppedItem()) continue;

            // ⭐ 执行前重验：扫描阶段拿到的「槽 → 物品」映射可能被前序功能改旧。
            // 整栈位移过的槽本拍跳过；同槽部分传输则重读当前对象，保留该槽 runtime。
            // 否则后续功能会拿旧 count / 组件执行，或把“只改数量”错当成机械位移。
            List<LivingItemFunction.SlotEntry> scanned = entry.getValue();
            List<LivingItemFunction.SlotEntry> entries = refreshEntries(
                entry.getKey(), scanned, context, tick);
            if (!scanned.isEmpty() && entries.isEmpty()
                    && !LivingItemManager.getSelfSustainingFunctions().contains(entry.getKey())) continue;
            entry.getKey().tick(entries, context, tick, level);
        }
    }

    /**
     * 把扫描期条目刷新为当前槽内容；被整栈移动的槽仍延迟到下一拍。
     *
     * <p>同槽部分传输会以新 {@link ItemStack} 替换旧对象，但不应作废该槽 runtime。
     * 因此不能把它塞进 {@link TickContext#movedSlots}；逐函数调用前重读即可同时守住
     * 「旧对象不再执行」与「只改数量不复位机械态」两条约束。</p>
     */
    private static List<LivingItemFunction.SlotEntry> refreshEntries(
            LivingItemFunction function,
            List<LivingItemFunction.SlotEntry> scanned,
            ContainerContext context,
            TickContext tick) {
        if (scanned.isEmpty()) return scanned;
        List<LivingItemFunction.SlotEntry> current = new ArrayList<>(scanned.size());
        for (LivingItemFunction.SlotEntry entry : scanned) {
            int slot = entry.slotIndex();
            if (tick.movedSlots.contains(slot)) continue;
            ItemStack stack = context.getItem(slot);
            if (function.canApply(stack)) {
                current.add(new LivingItemFunction.SlotEntry(slot, stack));
            }
        }
        return current;
    }

    /** 单遍全槽扫描产物：功能分组 + 内容签名 + 是否见到活物品（Q2：签名并入扫描循环，两遍降为一遍）。 */
    private record ScanResult(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            int contentSig,
            boolean hasAnyLivingItem) {}

    /**
     * 扫描容器全部槽位，将活物品按功能分组收集，同遍计算内容签名。
     *
     * <p>两阶段设计的「扫描」阶段：先按功能分组，再对每种功能只调用一次 tick，
     * 从根本上避免 N 个活物品 = N 倍速度（如活熔炉每 tick 只处理一个）。</p>
     *
     * <p>⚠️ {@code hasAnyLivingItem} 必须在循环里直接由 {@code isLivingItem} 置真，
     * <b>不能用 {@code !grouped.isEmpty()} 代答</b>：活黑曜石等「有活物品标记、无功能认领」的物品
     * （如地狱门框材，行为归容器级门面驱动）不进任何分组，但静息判定必须看见它们。</p>
     */
    private static ScanResult scanAndGroupLivingItems(ContainerContext context) {
        Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = new LinkedHashMap<>();
        int sig = 1;
        boolean anyLiving = false;
        for (int i = 0; i < context.getSize(); i++) {
            ItemStack stack = context.getItem(i);
            sig = mixSlot(sig, stack);
            if (LivingItemManager.isLivingItem(stack)) {
                anyLiving = true;
                var functions = LivingItemManager.getApplicableFunctions(stack);
                for (var function : functions) {
                    grouped.computeIfAbsent(function, k -> new ArrayList<>())
                            .add(new LivingItemFunction.SlotEntry(i, stack));
                }
            }
        }
        return new ScanResult(grouped, sig, anyLiving);
    }

    /**
     * 对拥有容器级数据（{@link HasContainerData}）的功能按优先级排序后逐个 tick。
     * 优先级高的先跑，确保依赖顺序（如活红石需先于下游消费状态）。
     */
    private static void runContainerDataTicks(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            ContainerContext context, TickContext tick) {
        List<Map.Entry<LivingItemFunction, List<LivingItemFunction.SlotEntry>>> hcdEntries = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            if (entry.getKey() instanceof HasContainerData) {
                hcdEntries.add(entry);
            }
        }
        hcdEntries.sort(Comparator.comparingInt(e -> ((HasContainerData) e.getKey()).getPriority()));

        for (var entry : hcdEntries) {
            List<LivingItemFunction.SlotEntry> scanned = entry.getValue();
            List<LivingItemFunction.SlotEntry> current = refreshEntries(
                entry.getKey(), scanned, context, tick);
            if (!scanned.isEmpty() && current.isEmpty()
                    && !LivingItemManager.getSelfSustainingFunctions().contains(entry.getKey())) continue;
            ((HasContainerData) entry.getKey()).tickContainerData(current, context, tick);
        }
    }

    /**
     * 处理指定位置的容器方块实体，构建上下文并执行活物品 tick。
     *
     * <p>此方法是 {@link #processBlockEntities} 和
     * {@link com.qiqi.li.LivingItem#processLevelContainers} 的公共逻辑提取，
     * 避免两个调用点重复编写"获取 handler → 大箱子检测 → 构建上下文 →
     * 处理"的流程。</p>
     *
     * @param level 世界
     * @param pos 容器方块位置
     * @param handler 已获取的 IItemHandler（如果为 null 则从能力系统获取）
     * @param processedHandlers 已处理的 IItemHandler 去重集合
     * @param processedKeys 已处理的容器缓存键去重集合（带维度）
     * @return 是否成功处理了该容器
     */
    public static boolean processContainerAt(Level level, BlockPos pos, IItemHandler handler,
                                              IdentityHashMap<IItemHandler, Boolean> processedHandlers,
                                              Set<String> processedKeys) {
        if (handler == null) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        }
        if (handler == null) return false;
        if (processedHandlers != null && processedHandlers.put(handler, Boolean.TRUE) != null) return false;

        TickableContainerContext context = resolveContextAt(level, pos, handler);
        if (context == null) return false;
        if (processedKeys != null && !processedKeys.add(context.getCacheKey())) return false;

        processIsolated(context, level);
        return true;
    }

    /**
     * 方块容器位置的**上下文反查**（2026-10-06 抽出，供能力查询等只读用途复用）。
     *
     * <p>与 tick 路径<b>同源同键</b>：同样走 {@code ItemHandler.BLOCK} 兼容面 + 同样的双箱
     * 规范化（{@link DoubleChestPositions#find}，LEFT 在前）⇒ 算出的 {@code containerKey} 与
     * tick 路径一致，<b>拿到的是同一份活数据实例</b>（同源原则）。</p>
     *
     * <p>跳过随机战利品容器（未开箱），与 {@link #processContainerAt} 同款规则。</p>
     *
     * @param handler 已获取的 {@link IItemHandler}（null = 从能力系统取）
     * @return 容器上下文；非容器 / 战利品未开箱 ⇒ {@code null}
     */
    public static TickableContainerContext resolveContextAt(Level level, BlockPos pos,
                                                            @javax.annotation.Nullable IItemHandler handler) {
        if (handler == null) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        }
        if (handler == null) return null;

        List<BlockPos> positions = new ArrayList<>();
        List<BlockEntity> blockEntities = new ArrayList<>();

        List<BlockPos> doubleChestPos = findDoubleChestPositions(level, pos);
        if (!doubleChestPos.isEmpty()) {
            for (BlockPos cp : doubleChestPos) {
                positions.add(cp);
                BlockEntity halfBe = level.getBlockEntity(cp);
                if (halfBe != null) {
                    if (halfBe instanceof RandomizableContainer rc && rc.getLootTable() != null) {
                        return null;
                    }
                    blockEntities.add(halfBe);
                }
            }
        } else {
            positions.add(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) {
                    return null;
                }
                blockEntities.add(be);
            }
        }

        return new SimpleContainerContext(handler, null, positions, blockEntities, level);
    }

    /**
     * 处理区块中的所有方块实体，对含容器的方块实体执行活物品 tick。
     *
     * 去重策略（双重保障）：
     * 1. IdentityHashMap 按 IItemHandler 实例去重（NeoForge 大箱子可能返回同一实例）
     * 2. HashSet 按 containerKey 去重（防止 IItemHandler 每次创建新实例时重复处理）
     *
     * ⚠️ 没有「活跃容器」跳过机制 —— 每个方块实体都会进入处理流程；
     * 空容器的开销由 {@code processContext} 的 {@code grouped.isEmpty()} 分支承担
     * （两次全槽位遍历：分组扫描 + 内容签名）。
     *
     * @param blockEntities 区块中的方块实体集合
     * @param level 世界
     * @param processedHandlers 已处理的 IItemHandler（用于去重）
     */
    public static void processBlockEntities(Iterable<BlockEntity> blockEntities, Level level,
                                            IdentityHashMap<IItemHandler, Boolean> processedHandlers) {
        Set<String> processedKeys = new HashSet<>();

        for (var be : blockEntities) {
            processContainerAt(level, be.getBlockPos(), null, processedHandlers, processedKeys);
        }
    }

    /**
     * 检测指定位置是否是大箱子，返回左右两半的位置（LEFT 在前，RIGHT 在后）。
     * 如果不是大箱子，返回空列表。
     *
     * <p>委托给 {@link DoubleChestPositions#find}，原实现已提取至该工具类。</p>
     */
    public static List<BlockPos> findDoubleChestPositions(Level level, BlockPos pos) {
        return DoubleChestPositions.find(level, pos);
    }
}