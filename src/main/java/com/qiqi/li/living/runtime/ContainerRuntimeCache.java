package com.qiqi.li.living.runtime;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端活物品运行时缓存 —— 存储每容器每槽位的运行时数据（遥测 / 瞬态字段）。
 *
 * <p>此类数据不写入 DataComponent，因此不会影响物品堆叠。数据由上层（L4）
 * 通过 {@link com.qiqi.li.network.LivingItemSyncPacket} 下发到客户端。</p>
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

    /** 玩家背包容器键前缀（buildContainerKey 的 inventory 分支） */
    public static final String PLAYER_KEY_PREFIX = "player_";

    private static final Map<String, Map<Integer, RuntimeSegments>> store = new ConcurrentHashMap<>();
    private static final Set<String> dirtyContainers = ConcurrentHashMap.newKeySet();

    // ── 更新 ────────────────────────────────────────────────

    public static void update(String containerKey, int slot, RuntimeSegments data) {
        store.computeIfAbsent(containerKey, k -> new ConcurrentHashMap<>()).put(slot, data);
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

    /**
     * 找出「正打开该容器界面」的玩家（纯查询，不发包）。
     *
     * <p><b>大箱子（v19.1 修复）</b>：原版大箱菜单的槽位容器是
     * {@link net.minecraft.world.CompoundContainer CompoundContainer}(左半 BE, 右半 BE)，
     * **不是 BE 本身**——旧逻辑 {@code containerInstances.contains(slot.container)}
     * 对大箱永远不匹配 → 同步包从不发给打开大箱的玩家 → 客户端遥测缓存为空 →
     * tooltip 永远显示 0/无数据（单箱菜单容器就是 BE 本体，故正常）。
     * 用 CompoundContainer 自带的 {@code contains(Container)} 匹配关联 BE。</p>
     *
     * <p>Q6 收编（2026-10-04）：判定实现已迁至
     * {@link com.qiqi.li.living.container.ContainerContexts#isViewing}。</p>
     *
     * @param players            候选玩家
     * @param containerInstances 该容器关联的 {@link Container} 实例集合
     * @return 正在查看其中任一实例的玩家
     */
    public static List<net.minecraft.server.level.ServerPlayer> findViewers(
            Collection<net.minecraft.server.level.ServerPlayer> players,
            Collection<Container> containerInstances) {
        List<net.minecraft.server.level.ServerPlayer> viewers = new ArrayList<>();
        for (var player : players) {
            if (ContainerContextsBridge.isViewing(player, containerInstances)) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    /**
     * 直接判定「该玩家菜单里是否含该容器的实例」—— 供 L4 内联使用（避免重复建 list）。
     */
    public static boolean isViewing(net.minecraft.server.level.ServerPlayer player,
                                    Collection<Container> containerInstances) {
        return ContainerContextsBridge.isViewing(player, containerInstances);
    }

    /** 薄桥：保持 runtime(L2) → container(L1) 的单向依赖，集中一处便于将来收编。 */
    private static final class ContainerContextsBridge {
        private ContainerContextsBridge() {}

        static boolean isViewing(net.minecraft.server.level.ServerPlayer player,
                                 Collection<Container> containerInstances) {
            return com.qiqi.li.living.container.ContainerContexts.isViewing(player, containerInstances);
        }
    }

    /** 判断玩家菜单是否只开着背包本身（=没开任何容器界面）—— 供 L4 过滤空转发包。 */
    public static boolean isJustInventoryMenu(net.minecraft.server.level.ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        return menu == null || menu == player.inventoryMenu;
    }

    /** 供 `ContainerContexts` 反向使用（保留对称入口，避免 future 直接 import container）。 */
    static boolean menuContains(AbstractContainerMenu menu, Collection<Container> containerInstances) {
        for (Slot slot : menu.slots) {
            if (containerInstances.contains(slot.container)) return true;
        }
        return false;
    }
}
