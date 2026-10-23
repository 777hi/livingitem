package com.qiqi.li.living.runtime;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 服务端活物品运行时缓存 —— 存储每容器每槽位的运行时数据（遥测 / 瞬态字段）。
 *
 * <p>此类数据不写入 DataComponent，因此不会影响物品堆叠。数据由上层（L4）
 * 通过 {@link com.qiqi.li.network.LivingItemSyncPacket} 下发到客户端。</p>
 *
 * <h3>键的形态（2026-10-12 统一）</h3>
 * <p>本表的键是<b>缓存键</b>而非裸容器键：世界容器 = {@code <维度>|chest_<x>_<y>_<z>}
 * （跨维度同坐标不串数据），玩家背包 / 末影箱 / 掉落物 = 裸键（数据跟人 / 跟实体走）。
 * 唯一定义点 = {@link com.qiqi.li.living.container.ContainerIdentity#getCacheKey()}；
 * 写入方一律传 {@code ctx.getCacheKey()}（<b>不要</b>传 {@code getContainerKey()} ——
 * 那正是 2026-10-12 修复的跨维度串数据 bug：主世界与下界同坐标的两个箱子
 * 共用一条燃料 / 进度，还每 tick 互相作废）。</p>
 *
 * <h3>脏标记与同步（职责边界 —— 档 2 反转发包后）</h3>
 * <p>每次更新数据时标记对应容器为"脏"。<b>本类不发送网络包</b>：只提供</p>
 * <ul>
 *   <li>{@link #drainDirty()} —— 交出本 tick 攒下的脏容器快照并清空脏集合，由 L4 发包；</li>
 *   <li>{@link #findViewers} —— 纯查询「哪些玩家正看着该容器」（供 L4 决定发给谁）。</li>
 * </ul>
 * <p>⚠️ <b>不要把发包搬回本类</b>：那会让 L2 依赖 L4 的网络包（R1 违规）。</p>
 *
 * <h3>⚠️ 服务端本地回读</h3>
 * <p>漏斗 / 熔炉用本缓存存放<b>跨 tick 的瞬态状态</b>（冷却、烧制进度）——
 * 不只是"给客户端看的"。⇒ {@link #get} 的语义必须保持「读回自己上一 tick 写下的值」。</p>
 */
public class ContainerRuntimeCache {

    /**
     * 玩家容器键前缀 —— ⚠️ <b>2026-10-13 起键形解析的唯一入口是
     * {@link com.qiqi.li.living.container.ContainerKeys#PLAYER_PREFIX}</b>，
     * 本常量仅为历史引用保留，新代码请勿使用（发包侧已改用
     * {@code ContainerKeys.kindOf} 判种类）。
     *
     * @deprecated 用 {@link com.qiqi.li.living.container.ContainerKeys#PLAYER_PREFIX}
     */
    @Deprecated
    public static final String PLAYER_KEY_PREFIX = "player_";

    private static final Map<String, Map<Integer, RuntimeSegments>> store = new ConcurrentHashMap<>();
    private static final Set<String> dirtyContainers = ConcurrentHashMap.newKeySet();

    static {
        // static 缓存清理：登记与 clear() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        StaticCacheRegistry.onServerStop(s -> ContainerRuntimeCache.clear());
    }

    // ── 更新 ────────────────────────────────────────────────

    /**
     * 写入某槽位的运行时数据。
     *
     * <p>⚠️ <b>收 {@link ContainerContext} 而不是裸键 + 槽号</b>（2026-10-21）：本缓存是
     * <b>对外</b>通道（客户端 tooltip 按 {@code Slot#getContainerSlot()} 查），所以键必须是
     * <b>物理槽</b>；而领域侧手里的 {@code slot} 是<b>逻辑槽</b>。让本方法自己过
     * {@link ContainerContext#toPhysicalSlot} ⇒ 调用方不可能漏映射（漏了就显示偏位）。</p>
     */
    public static void update(com.qiqi.li.living.container.ContainerContext ctx,
                              int logicalSlot, RuntimeSegments data) {
        if (ctx == null) return;
        // cacheKey 正常 = 稳定键 + 维度前缀；测试替身没有维度 ⇒ 回落到稳定键
        String containerKey = ctx.getCacheKey() != null ? ctx.getCacheKey() : ctx.getContainerKey();
        if (containerKey == null) return;
        int physicalSlot = ctx.toPhysicalSlot(logicalSlot);
        store.computeIfAbsent(containerKey, k -> new ConcurrentHashMap<>()).put(physicalSlot, data);
        dirtyContainers.add(containerKey);
    }

    public static void removeSlot(String containerKey, int slot) {
        Map<Integer, RuntimeSegments> slots = store.get(containerKey);
        if (slots != null) {
            slots.remove(slot);
            dirtyContainers.add(containerKey);
        }
    }

    public static void removeContainer(String containerKey) {
        store.remove(containerKey);
        dirtyContainers.remove(containerKey);
    }

    /**
     * 清空全部运行时缓存（服务端关闭时调用）。
     *
     * <p>⚠️ <b>必须登记</b>：单机「退出存档 → 进另一个存档」<b>不重启 JVM</b>，static 字段跨存档存活。
     * 容器键 = 维度 + 坐标（+ 背包前缀）⇒ 两个存档里同一个箱子坐标相同 ⇒ <b>新存档会继承
     * 上一个存档的燃料 / 进度 / 相位</b>（<code>StaticCacheRegistry</code> 铁律 A1）。
     * 登记就在<b>本类静态块</b>（2026-10-12 起：缓存类自己登记，见 StaticCacheRegistry 类注释）。</p>
     */
    public static void clear() {
        store.clear();
        dirtyContainers.clear();
    }

    // ── 读取 ────────────────────────────────────────────────

    public static RuntimeSegments get(String containerKey, int slot) {
        Map<Integer, RuntimeSegments> slots = store.get(containerKey);
        if (slots == null) return RuntimeSegments.EMPTY;
        return slots.getOrDefault(slot, RuntimeSegments.EMPTY);
    }

    public static Map<Integer, RuntimeSegments> getSnapshot(String containerKey) {
        Map<Integer, RuntimeSegments> slots = store.get(containerKey);
        if (slots == null) return Map.of();
        return Map.copyOf(slots);
    }

    /** 是否有尚未取走的脏容器（诊断 / 短路用）。 */
    public static boolean hasDirty() {
        return !dirtyContainers.isEmpty();
    }

    // ── 同步（发包职责在 L4）─────────────────────────────────

    /**
     * 取出本 tick 攒下的脏容器快照并清空脏集合 —— 由 L4 消费并发包（档 2 反转发包）。
     *
     * <p>「取快照 → 清脏」顺序与改造前一致：先取快照保证本 tick 的登记不丢，
     * 再清脏保证同一容器下一 tick 可重新登记。</p>
     *
     * @return 容器键 → （槽位 → 片段集合）的快照；无脏容器时为空 map
     */
    public static Map<String, Map<Integer, RuntimeSegments>> drainDirty() {
        if (dirtyContainers.isEmpty()) return Map.of();

        Set<String> flushed = new HashSet<>(dirtyContainers);
        dirtyContainers.clear();

        Map<String, Map<Integer, RuntimeSegments>> out = new LinkedHashMap<>();
        for (String containerKey : flushed) {
            Map<Integer, RuntimeSegments> snapshot = getSnapshot(containerKey);
            if (snapshot.isEmpty()) continue;
            out.put(containerKey, snapshot);
        }
        return out;
    }
}
