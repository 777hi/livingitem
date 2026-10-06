package com.qiqi.li.living.domain.water;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.neoforged.neoforge.fluids.FluidType;

/**
 * 客户端容器流体快照缓存 —— 由 {@link com.qiqi.li.network.FluidFlowSyncPacket} 更新，
 * 供容器界面的水流渲染读取（Q5 渲染轨：容器级同步，不依赖活水桶物品）。
 *
 * <p>与 {@code LivingItemClientCache} 同构的「最后快照」模型：服务端只发给正在查看
 * 该容器的玩家，客户端不解析 containerKey，只存最近一次收到的快照。
 * 玩家背包与 BE 容器分两份存（防串台），界面关闭时 {@link #clear()}。</p>
 *
 * <p>cell 值 = {@code int[]{level, fromSlot, fluidIndex}}（fluidIndex 为本快照
 * {@code fluids} 调色板下标）。本类不依赖 Minecraft 客户端类，可在服务端安全加载。</p>
 */
public final class FluidFlowClientCache {

    /** 一次容器流体快照：宽度 + 流体调色板 + 槽位 → [level, fromSlot, fluidIndex]。 */
    public record FlowSnapshot(int width, List<FluidType> fluids, Map<Integer, int[]> cells) {
        public static final FlowSnapshot EMPTY = new FlowSnapshot(9, List.of(), Map.of());
        public boolean isEmpty() { return cells.isEmpty(); }
    }

    /**
     * 快照的**渲染目标**（服务端权威告知）—— 2026-10-06 教训：
     * <b>客户端无法推断「当前界面是哪个容器」</b>：原版末影箱 GUI 在客户端是
     * {@code MenuType.GENERIC_9x3} + 一个 27 格 {@code SimpleContainer} 替身
     * （真实容器 {@code PlayerEnderChestContainer} 只在服务端），
     * 与「普通 3 行箱子界面」在客户端**完全同形** ⇒
     * 靠 {@code slot.container instanceof} 判末影箱是<b>死分支</b>
     * （一度表现为「末影箱的水一点都不渲染」；更早则表现为「泄漏到玩家物品栏」）。
     * ⇒ 改由服务端在包里明确带上目标，客户端只做路由。
     */
    public enum RenderTarget { PLAYER_INV, ENDER_CHEST, BLOCK }

    private static volatile FlowSnapshot containerSnapshot = FlowSnapshot.EMPTY;
    private static volatile FlowSnapshot playerSnapshot = FlowSnapshot.EMPTY;
    private static volatile FlowSnapshot enderSnapshot = FlowSnapshot.EMPTY;

    /** 「非背包组」的快照当前该取哪个槽 —— 由最近一次服务端下发决定（见 {@link RenderTarget}）。 */
    private static volatile RenderTarget chestLikeTarget = RenderTarget.BLOCK;

    private FluidFlowClientCache() {}

    /** 更新缓存（网络包线程调用）：按服务端下发的 {@link RenderTarget} 路由（不再靠键前缀猜）。 */
    public static void update(RenderTarget target, FlowSnapshot snapshot) {
        switch (target) {
            case PLAYER_INV -> playerSnapshot = snapshot;
            case ENDER_CHEST -> {
                enderSnapshot = snapshot;
                chestLikeTarget = RenderTarget.ENDER_CHEST;
            }
            case BLOCK -> {
                containerSnapshot = snapshot;
                chestLikeTarget = RenderTarget.BLOCK;
            }
        }
    }

    /**
     * <b>非背包组</b>（界面里除玩家背包外的那些槽位）该用的快照。
     *
     * <p>末影箱界面与 BE 容器界面在客户端同形（见 {@link RenderTarget}）⇒ 只能由服务端
     * 最近一次下发的目标决定；界面关闭时 {@link #clear()} 会重置为 BLOCK/EMPTY，
     * 所以「打开一个没有流体的容器」不会闪现上一个界面的水。</p>
     */
    public static FlowSnapshot getChestLike() {
        return chestLikeTarget == RenderTarget.ENDER_CHEST ? enderSnapshot : containerSnapshot;
    }

    /** 当前玩家背包快照（无数据返回 EMPTY）。 */
    public static FlowSnapshot getPlayer() {
        return playerSnapshot;
    }

    /** 清空三份缓存 + 目标提示（界面关闭时调用，防止下次打开别的容器闪现旧水）。 */
    public static void clear() {
        containerSnapshot = FlowSnapshot.EMPTY;
        playerSnapshot = FlowSnapshot.EMPTY;
        enderSnapshot = FlowSnapshot.EMPTY;
        chestLikeTarget = RenderTarget.BLOCK;   // 重置为默认（未收到提示 ⇒ 非背包组取 EMPTY）
    }

    /** 便捷拷贝（网络包处理构造快照用）。 */
    public static FlowSnapshot copyOf(int width, List<FluidType> fluids, Map<Integer, int[]> cells) {
        return new FlowSnapshot(width, List.copyOf(fluids), new HashMap<>(cells));
    }
}
