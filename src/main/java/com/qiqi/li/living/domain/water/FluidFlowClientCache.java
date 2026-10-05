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

    private static volatile FlowSnapshot containerSnapshot = FlowSnapshot.EMPTY;
    private static volatile FlowSnapshot playerSnapshot = FlowSnapshot.EMPTY;
    /** 末影箱（2026-10-06）：键 player_<uuid>_ender_chest 与背包同前缀 ⇒ 必须独立槽位，
     *  否则末影箱的水会渲染到玩家物品栏（实测泄漏）。 */
    private static volatile FlowSnapshot enderSnapshot = FlowSnapshot.EMPTY;

    private FluidFlowClientCache() {}

    /** 更新缓存（网络包线程调用）：按键路由三槽位（背包 / 末影箱 / BE 容器）。 */
    public static void update(String containerKey, FlowSnapshot snapshot) {
        if (containerKey != null && containerKey.startsWith("player_")) {
            if (containerKey.endsWith("_ender_chest")) {
                enderSnapshot = snapshot;
            } else {
                playerSnapshot = snapshot;
            }
        } else {
            containerSnapshot = snapshot;
        }
    }

    /** 当前打开的 BE 容器快照（无数据返回 EMPTY）。 */
    public static FlowSnapshot get() {
        return containerSnapshot;
    }

    /** 当前玩家背包快照（无数据返回 EMPTY）。 */
    public static FlowSnapshot getPlayer() {
        return playerSnapshot;
    }

    /** 当前末影箱快照（无数据返回 EMPTY）。 */
    public static FlowSnapshot getEnder() {
        return enderSnapshot;
    }

    /** 清空三份缓存（界面关闭时调用，防止下次打开别的容器闪现旧水）。 */
    public static void clear() {
        containerSnapshot = FlowSnapshot.EMPTY;
        playerSnapshot = FlowSnapshot.EMPTY;
        enderSnapshot = FlowSnapshot.EMPTY;
    }

    /** 便捷拷贝（网络包处理构造快照用）。 */
    public static FlowSnapshot copyOf(int width, List<FluidType> fluids, Map<Integer, int[]> cells) {
        return new FlowSnapshot(width, List.copyOf(fluids), new HashMap<>(cells));
    }
}
