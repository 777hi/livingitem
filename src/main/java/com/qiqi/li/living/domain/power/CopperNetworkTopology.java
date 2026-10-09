package com.qiqi.li.living.domain.power;

import java.util.Arrays;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.util.WaxedCopperFamily;

/**
 * 铜块网络拓扑原语 —— 2026-10-09 从 {@link LivingWaxedCopperFunction} 抽出
 * （power 收口方案 步骤 4 第 3 刀：纯搬迁，零逻辑变更）。
 *
 * <p><b>边界说明（为什么只抽这些）</b>：本类只放「与 tick 流程无关的纯图/键运算」——
 * 方向映射、邻接判定、组件 rep 表、边键编码、雕文输入边解析。<b>BFS 驱动循环
 * （{@code runBfs}）与感应诊断日志仍留在 {@link LivingWaxedCopperFunction}</b>：</p>
 * <ul>
 *   <li>{@code runBfs} 混合了「拓扑遍历 + 边信号检测 + 通道事件注入」，属 tick 流程；</li>
 *   <li>它会调用 {@link PhaseInterpreter#derivedSourceId} 并操作顶层 {@link SignalTracker}；
 *       两者现在均已从功能类解耦，但 BFS 仍留在编排类，因为它负责把拓扑遍历接到通道事件流，
 *       而不只是纯拓扑计算。</li>
 * </ul>
 *
 * <p>这些原语被 BFS 驱动<b>与</b> {@link PhaseInterpreter} 共用（{@code edgeKey} / {@code FALLING_BIT}
 * 在 {@code interpretShifter} / {@code interpretSplitter} 里也用到），故独立成类不会造成
 * 单向依赖。</p>
 */
final class CopperNetworkTopology {

    private CopperNetworkTopology() {}

    /** BFS 方向偏移：EDGE_UP/DOWN/LEFT/RIGHT 对应的 (行,列) 增量 */
    static final int[] DIR_ROW = {-1, 1, 0, 0};
    static final int[] DIR_COL = {0, 0, -1, 1};

    /** 下降沿跟踪器命名空间位（与上升沿跟踪器同表，位隔离） */
    static final long FALLING_BIT = 1L << 32;

    /**
     * 将 Pos2D 方向映射为 ContainerRedstoneData 的边方向索引。
     * UP=(0,-1) → 0, DOWN=(0,1) → 1, LEFT=(-1,0) → 2, RIGHT=(1,0) → 3
     */
    static int pos2dToEdgeDir(Pos2D dir) {
        // v19.1：值比较而非引用比较——Pos2D 经序列化/反序列化后是值相等的新实例，
        // 引用比较会让配置过的方向全部落入 fallback（恒 UP）。
        if (dir.x() == 0) {
            return dir.y() < 0 ? RedstoneSensor.EDGE_UP : RedstoneSensor.EDGE_DOWN;
        }
        return dir.x() < 0 ? RedstoneSensor.EDGE_LEFT : RedstoneSensor.EDGE_RIGHT;
    }

    /**
     * 从 current 沿 dir 是否可达同氧化级铜块邻居；可达返回邻居槽位，否则 -1。
     * v19：网络连通性只由氧化等级决定（形态不再约束连通——个性迁移到解读规则）。
     * runBfs 与组件分组共用，避免两套邻接逻辑分叉。
     */
    static int traversableNeighbor(ContainerContext ctx, int size, int width,
            int current, int dir, int oxidation) {
        int row = current / width;
        int col = current % width;
        int nr = row + DIR_ROW[dir];
        int nc = col + DIR_COL[dir];
        if (nr < 0 || nc < 0 || nc >= width) return -1;
        int neighbor = nr * width + nc;
        if (neighbor < 0 || neighbor >= size) return -1;
        ItemStack ns = ctx.getItem(neighbor);
        if (ns.isEmpty()) return -1;
        if (!WaxedCopperFamily.isWaxedCopperBlock(ns.getItem())) return -1;
        if (WaxedCopperFamily.isWaxedBulb(ns.getItem())) return -1; // 铜灯不导电
        if (WaxedCopperFamily.getOxidationLevel(ns.getItem()) != oxidation) return -1;
        return neighbor;
    }

    /**
     * 每 tick 每锈级建一次组件 rep 表；返回 slot 所属组件 rep（组件内最小槽位）。
     * rep 稳定（= 最小铜块槽位），保证跨 tick 同一网络映射到同一 ChannelState 实例。
     */
    static int repOf(int oxidation, int slot, Map<Integer, int[]> cache,
            ContainerContext ctx, int size, int width) {
        int[] arr = cache.get(oxidation);
        if (arr == null) {
            arr = new int[size];
            Arrays.fill(arr, -1);
            int[] comp = new int[size];
            int[] q = new int[size];
            for (int s = 0; s < size; s++) {
                if (arr[s] != -1) continue;
                ItemStack st = ctx.getItem(s);
                if (st.isEmpty() || !WaxedCopperFamily.isWaxedCopperBlock(st.getItem()) || WaxedCopperFamily.isWaxedBulb(st.getItem())) continue;
                if (WaxedCopperFamily.getOxidationLevel(st.getItem()) != oxidation) continue;
                // 收集该组件全部槽位（弱连通）
                int cn = 0, h = 0, t = 0;
                q[t++] = s; arr[s] = s; comp[cn++] = s;
                while (h < t) {
                    int cur = q[h++];
                    for (int dir = 0; dir < RedstoneSensor.DIRECTIONS; dir++) {
                        int nb = traversableNeighbor(ctx, size, width, cur, dir, oxidation);
                        if (nb < 0 || arr[nb] != -1) continue;
                        arr[nb] = s; comp[cn++] = nb; q[t++] = nb;
                    }
                }
                int rep = s;
                for (int i = 0; i < cn; i++) rep = Math.min(rep, comp[i]);
                for (int i = 0; i < cn; i++) arr[comp[i]] = rep;
            }
            cache.put(oxidation, arr);
        }
        return arr[slot];
    }

    /** 组件标识：氧化等级 + 组件内最小铜块槽位（v19：连通性唯一维度 = 氧化等级） */
    record NetworkKey(int oxidation, int rep) {}

    /** 边跟踪器键：(slot << 2) | dir */
    static long edgeKey(int slot, int dir) {
        return ((long) slot << 2) | dir;
    }

    /** 测试可见：Pos2D → 边方向的映射（值比较，序列化实例安全） */
    static int chiseledInputEdgeForTest(Pos2D dir) {
        return pos2dToEdgeDir(dir);
    }

    /**
     * 雕文槽位的发电采样方向（仅感应输入方向，镜像信号层二极管语义）；非雕文返回 -1（全向）。
     */
    static int chiseledInputEdge(ContainerContext ctx, int slot) {
        ItemStack stack = ctx.getItem(slot);
        if (stack.isEmpty() || !WaxedCopperFamily.isWaxedChiseled(stack.getItem())) return -1;
        return pos2dToEdgeDir(LivingWaxedChiseledData.of(stack).inputDir());
    }
}
