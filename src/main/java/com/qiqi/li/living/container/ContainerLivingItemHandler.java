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

import com.qiqi.li.living.api.ContainerDataLifecycle;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.util.DoubleChestPositions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
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

    /**
     * 单一容器数据条目 —— 合并原 6 张同键 Map（流体 / 红石 / 电力 / 修订计数 /
     * 内容签名 / 快照缓存）为一张 {@code Map<String, ContainerEntry>}，消除三套
     * 几乎一字不差的 get-or-create 与 {@code cleanupStalePosIndex} 里重复 4 遍的
     * 「无流体且无红石且无电力」判定。见 {@link #entry} 与 {@link #cleanupStaleData}。
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
    }

    private static final int CLEANUP_INTERVAL = 1200;
    private static int cleanupCounter;

    /** 维度 + 坐标，作为位置索引的键（含维度，避免跨维度同坐标冲突） */
    private record PosKey(ResourceKey<Level> dim, BlockPos pos) {}

    /**
     * 构建容器数据的缓存键。
     *
     * <p>在 {@code containerKey} 前拼接维度，避免主世界与下界同坐标的两个容器
     * 共用同一条流体/红石数据。{@code containerKey} 自身格式保持不变，
     * 以兼容末影频道对其的解析。</p>
     *
     * @return 缓存键，容器无 containerKey 时返回 null
     */
    private static String cacheKey(ContainerContext ctx) {
        String key = ctx.getContainerKey();
        if (key == null) return null;
        Level level = ctx.getLevel();
        if (level == null || level.dimension() == null) return key;
        return level.dimension().location() + "|" + key;
    }

    /** 为容器关联的所有方块位置建立反向索引 */
    private static void indexPositions(ContainerContext ctx, String cacheKey) {
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
     * 首次创建时顺便建位置反向索引；context 无 containerKey 时返回 null。
     */
    private static ContainerEntry entry(ContainerContext ctx) {
        String key = cacheKey(ctx);
        if (key == null) return null;
        ContainerEntry e = CONTAINER_DATA.get(key);
        if (e == null) {
            e = new ContainerEntry();
            CONTAINER_DATA.put(key, e);
            indexPositions(ctx, key);
        }
        return e;
    }

    /**
     * 清理指定位置容器的流体/红石/电力数据（容器方块被破坏时调用）。
     */
    public static void removeDataByPos(Level level, BlockPos pos) {
        String key = POS_TO_CACHE_KEY.remove(new PosKey(level.dimension(), pos.immutable()));
        if (key == null) return;
        CONTAINER_DATA.remove(key);
        POS_TO_CACHE_KEY.values().removeIf(key::equals);
    }

    /**
     * 清理过期的流体/红石/电力数据（超过 120s 未访问的整条容器条目回收）。
     * 仅当条目内<b>所有存在的数据类型</b>都过期才回收，避免误丢仍活跃的单一类型数据。
     */
    private static void cleanupStaleData(long currentTimeMs) {
        CONTAINER_DATA.entrySet().removeIf(en -> {
            ContainerEntry e = en.getValue();
            // 泛化（2026-10-08 计划 ⑤）：遍历已登记的 key，只问「实现了生命周期接口」的数据
            // （跨 tick 持久那几种）；tick 级数据（如应力）每 tick 重建，不参与过期判定。
            for (ContainerDataKey<?> key : ContainerDataKey.all()) {
                Object v = e.store.peek(key);
                if (v instanceof ContainerDataLifecycle lc
                        && currentTimeMs - lc.getLastTickTime() <= 120_000) {
                    return false;   // 有任一数据仍活跃 ⇒ 不回收整条
                }
            }
            return true;
        });
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
     * 泛型访问容器级持久数据（不创建）。供 {@code SimpleContainerContext} 的
     * {@code peekContainerData} 覆写委托 —— 按 key 走统一 store，无需按类型 switch。
     */
    public static <T> T peekContainerData(ContainerContext ctx, ContainerDataKey<T> key) {
        ContainerEntry e = entry(ctx);
        return e == null ? null : e.store.peek(key);
    }

    /** 泛型访问或创建容器级持久数据。见 {@link #peekContainerData(ContainerContext, ContainerDataKey)}。 */
    public static <T> T getOrCreateContainerData(ContainerContext ctx, ContainerDataKey<T> key) {
        ContainerEntry e = entry(ctx);
        return e == null ? key.create() : e.store.getOrCreate(key);
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
     * <p>只比对「物品 id + 数量」：自定义 DataComponent 的变更只可能由本模组发起，
     * 而那些路径已经走了 {@code syncSlotToClients}（会 bump），无需在此重复覆盖。</p>
     */
    public static void syncContentRevision(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return;
        int sig = computeContentSignature(ctx);
        int prev = e.contentSig;
        e.contentSig = sig;
        if (prev == Integer.MIN_VALUE || prev != sig) {
            bumpContainerRevision(ctx);
        }
    }

    /** 容器内容签名：逐槽位混入 物品 id 与数量（空槽参与混合，保证槽位移动也能检出） */
    private static int computeContentSignature(ContainerContext ctx) {
        int h = 1;
        int size = ctx.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack s = ctx.getItem(i);
            if (s == null || s.isEmpty()) {
                h = h * 31;
            } else {
                h = h * 31 + Item.getId(s.getItem()) * 31 + s.getCount();
            }
        }
        return h;
    }

    /**
     * 取（或构建并缓存）容器快照。仅当修订计数相对上次构建发生变化时才重建，
     * 否则复用跨 tick 缓存的同一快照，避免每个 tick 重复扫描全部物品。
     */
    public static ContainerSnapshot getCachedSnapshot(ContainerContext ctx, long revision,
                                                      TickContext tick) {
        ContainerEntry e = entry(ctx);
        if (e == null) return ContainerSnapshot.capture(ctx, tick);
        if (e.cachedSnapshot != null && e.cachedSnapshotRevision == revision) return e.cachedSnapshot;
        ContainerSnapshot snap = ContainerSnapshot.capture(ctx, tick);
        e.cachedSnapshotRevision = revision;
        e.cachedSnapshot = snap;
        return snap;
    }

    /**
     * 清理已失去主缓存条目的位置索引。
     * 仅当对应 cacheKey 在 {@link #CONTAINER_DATA} 中不存在或条目内已无任何子数据时，
     * 才回收其位置反向索引（快照/修订计数/内容签名随条目一起被 {@link #cleanupStaleData} 回收）。
     */
    private static void cleanupStalePosIndex() {
        POS_TO_CACHE_KEY.values().removeIf(
            key -> {
                ContainerEntry e = CONTAINER_DATA.get(key);
                return e == null || e.store.isEmpty();
            });
    }

    /** 清空全部容器级缓存（服务端关闭时调用，避免跨存档残留） */
    public static void clearAllCaches() {
        CONTAINER_DATA.clear();
        POS_TO_CACHE_KEY.clear();
        cleanupCounter = 0;
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
        processContext(context, level);

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
        processContext(context, level);
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

        String monitorKey = context.getContainerKey();
        com.qiqi.li.living.debug.ContainerMonitor.beforeProcess(monitorKey, context);

        // 阶段 1：扫描容器全部槽位，将活物品按功能分组，同时校验内容签名
        Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = scanAndGroupLivingItems(context);
        syncContentRevision(context);

        // 自维持功能（如活水源）：即便容器内没有它自己的活物品，也要进入主流程参与 tick。
        // 遍历的是注册期算好的静态清单（通常 0~1 个），不逐函数判定，保证成千上万个容器
        // 下的每 tick 开销可控（详见 LivingItemFunction#shouldTickWithoutOwnItems）。
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
        context.setTickContext(tick);

        long scanEndNanos = System.nanoTime();
        PerfMetrics.recordPhase("scan", scanEndNanos - startNanos);

        long stressEndNanos;
        try {
            // 阶段 2：功能 tick（按优先级执行各功能的 tick 逻辑）
            tickFunctionSlots(grouped, tick);
            runFunctionTicks(grouped, context, tick, level);

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
            // 与过期清理。2026-10-08 计划 ⑤：原 writebackBlockEntities 的领域逻辑已移入各领域。
            ContainerTickHooks.fireWriteback(context, tick);
            incrementCleanup();

            stressEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("stress", stressEndNanos - containerDataEndNanos);
        } finally {
            // 无论功能 tick / 容器级数据 / 写回是否抛异常，都同步脏槽到客户端并清掉 stale
            // TickContext：否则异常时客户端物品显示错位，且下一 tick 残留旧上下文（P1-6）。
            context.flushDirtySlots();
            context.setTickContext(null);
        }

        // 阶段 6：脏槽刷新（from finally）后的 perf 收尾
        long flushSlotsEndNanos = System.nanoTime();
        PerfMetrics.recordPhase("flush_slots", flushSlotsEndNanos - stressEndNanos);

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
     * 执行各功能的 tick 逻辑（按优先级）。
     * 每个功能只调用一次 tick，传入其管理的所有活物品槽位。
     */
    private static void runFunctionTicks(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            ContainerContext context, TickContext tick, Level level) {
        for (var entry : grouped.entrySet()) {
            entry.getKey().tick(entry.getValue(), context, tick, level);
        }
    }

    /**
     * 递增清理计数器，达到间隔时执行过期数据清理。
     */
    private static void incrementCleanup() {
        cleanupCounter++;
        if (cleanupCounter >= CLEANUP_INTERVAL) {
            cleanupCounter = 0;
            long currentTimeMs = System.currentTimeMillis();
            cleanupStaleData(currentTimeMs);
            cleanupStalePosIndex();
        }
    }

    /**
     * 扫描容器全部槽位，将活物品按功能分组收集。
     *
     * <p>两阶段设计的「扫描」阶段：先按功能分组，再对每种功能只调用一次 tick，
     * 从根本上避免 N 个活物品 = N 倍速度（如活熔炉每 tick 只处理一个）。</p>
     */
    private static Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> scanAndGroupLivingItems(
            ContainerContext context) {
        Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < context.getSize(); i++) {
            ItemStack stack = context.getItem(i);
            if (LivingItemManager.isLivingItem(stack)) {
                var functions = LivingItemManager.getApplicableFunctions(stack);
                for (var function : functions) {
                    grouped.computeIfAbsent(function, k -> new ArrayList<>())
                            .add(new LivingItemFunction.SlotEntry(i, stack));
                }
            }
        }
        return grouped;
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
            ((HasContainerData) entry.getKey()).tickContainerData(entry.getValue(), context, tick);
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
     * @param processedKeys 已处理的 containerKey 去重集合
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
        if (processedKeys != null && !processedKeys.add(context.getContainerKey())) return false;

        processContext(context, level);
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