package com.qiqi.li.living.domain.beacon;

import javax.annotation.Nullable;

/**
 * 下方一行 9 列的「1D 金字塔」判定器（设计稿 {@code docs/buffer/living-beacon-design.md} §2.4）。
 *
 * <p><b>模型</b>：活信标所在槽位正下方整整一行 9 个槽位 = 金字塔底座，
 * <b>以信标所在列为中心</b>（{@link #CENTER} = 信标本列，两侧各 4 列；越出容器边界的列视为 0）。
 * 每槽的矿物块堆叠数 = 该列的<b>柱高</b> —— 它是原版 4 层方锥的 1D 投影：
 * 每列柱高 = 覆盖该列的各层边宽之和（L4 中心 = 9+7+5+3 = 24，边列 = 9）。
 * 合计 9 / 34 / 83 / 164 与原版逐字一致，形状嵌套单调（L4⟹L3⟹L2⟹L1）。</p>
 *
 * <p><b>判定</b>（用户 2026-10-15 定）：每列 ≥ 目标、取满足的最高 N —— 少一块自动掉档、
 * 多堆不亏。无一满足 ⇒ 0 级（未激活 = 完全死物）。</p>
 *
 * <p>纯逻辑、无 MC 依赖（输入 long[9] 柱高），服务端 tick 与客户端本地门控共用同一份判据。</p>
 */
public final class BeaconPyramid {

    /** 底座列数（信标下方一行 9 槽）。 */
    public static final int COLUMNS = 9;

    /** 最高等级（满级）。 */
    public static final int MAX_LEVEL = 4;

    /** 中心列下标：heights[{@value #CENTER}] = 信标所在列。 */
    public static final int CENTER = COLUMNS / 2;

    /**
     * 各等级目标柱高（{@code TARGETS[等级-1][列]}），左半 → 中心；0 = 该列不参与本级。
     *
     * <pre>
     * L1: 0-0-0-3-3-3-0-0-0   （中 3 列，合计 9）
     * L2: 0-0-5-8-8-8-5-0-0   （中 5 列，合计 34）
     * L3: 0-7-12-15-15-15-12-7-0 （中 7 列，合计 83）
     * L4: 9-16-21-24-24-24-21-16-9（全 9 列，合计 164）
     * </pre>
     */
    private static final long[][] TARGETS = {
        {0, 0, 0, 3, 3, 3, 0, 0, 0},
        {0, 0, 5, 8, 8, 8, 5, 0, 0},
        {0, 7, 12, 15, 15, 15, 12, 7, 0},
        {9, 16, 21, 24, 24, 24, 21, 16, 9},
    };

    private BeaconPyramid() {
    }

    /**
     * 判级：从 4 往 1 依次试，取满足的最高 N；全不满足 ⇒ 0。
     *
     * @param heights 9 列柱高（下标 {@link #CENTER} 为信标本列；可为 null 或不足 9 长，缺省视为 0）
     * @return 0~4
     */
    public static int resolveLevel(@Nullable long[] heights) {
        for (int n = MAX_LEVEL; n >= 1; n--) {
            if (meets(heights, n)) {
                return n;
            }
        }
        return 0;
    }

    /** 该级是否满足（每根参与列 ≥ 目标）。 */
    public static boolean meets(@Nullable long[] heights, int level) {
        if (level < 1 || level > MAX_LEVEL) return false;
        long[] target = TARGETS[level - 1];
        for (int c = 0; c < COLUMNS; c++) {
            if (target[c] > 0 && heightAt(heights, c) < target[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 距下一级（level+1）各列还差多少（已满足的列 = 0；满级返回全 0）。
     *
     * <p>tooltip「距下一级还差 X 块 —— 缺列：左3 差 5」即由此算出。</p>
     */
    public static int[] deficitsToNext(int level, @Nullable long[] heights) {
        int[] deficits = new int[COLUMNS];
        if (level < 0 || level >= MAX_LEVEL) {
            return deficits;
        }
        long[] target = TARGETS[level]; // level+1 的目标（level 为 0 基下标）
        for (int c = 0; c < COLUMNS; c++) {
            long deficit = target[c] - heightAt(heights, c);
            if (deficit > 0) {
                deficits[c] = (int) deficit;
            }
        }
        return deficits;
    }

    /** 距下一级还差的总块数（各列缺口之和）。 */
    public static long blocksToNext(int level, @Nullable long[] heights) {
        long sum = 0;
        for (int d : deficitsToNext(level, heights)) {
            sum += d;
        }
        return sum;
    }

    /** 该等级 buff 范围（格）—— 照原版公式 {@code 等级×10+10}（设计稿 §2.5 / §4）。 */
    public static double rangeBlocks(int level) {
        return level * 10.0 + 10.0;
    }

    /** 该等级底座合计柱高（0 / 9 / 34 / 83 / 164）—— 信息展示用。 */
    public static long totalForLevel(int level) {
        if (level < 1 || level > MAX_LEVEL) return 0;
        long sum = 0;
        for (long v : TARGETS[level - 1]) {
            sum += v;
        }
        return sum;
    }

    /** 列的人读标签（tooltip 用）：中 / 左N / 右N。 */
    public static String columnLabel(int column) {
        if (column == CENTER) return "中";
        int distance = Math.abs(column - CENTER);
        return (column < CENTER ? "左" : "右") + distance;
    }

    private static long heightAt(@Nullable long[] heights, int column) {
        if (heights == null || column < 0 || column >= heights.length) {
            return 0;
        }
        return Math.max(0, heights[column]);
    }
}
