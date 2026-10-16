package com.qiqi.li.living.domain.beacon;

/**
 * 9 柱小金字塔面板的几何纯函数（设计稿 §2.6.2，Phase 4）。
 *
 * <p>与 {@link BeaconPyramid} 一样无 MC 依赖 ⇒ 可全量单测（刻度 y / 柱顶 y / 缺口高度 /
 * 数字取舍 / 可达性）。渲染器 {@code LivingBeaconTooltipRenderer} 只做绘制，
 * 所有坐标判定走本类——「像素对不上」类 bug 在单测层拦掉，不靠启动游戏截图。</p>
 *
 * <p>坐标系统一用<b>面板左上角为原点</b>、y 向下；{@code fill} 右/下端排他
 * （渲染器注意）。</p>
 */
public final class BeaconPanelGeometry {

    // ── 面板与柱位 ──────────────────────────────────────────────
    public static final int PANEL_W = 160;
    public static final int PANEL_H = 76;
    /** 柱位槽宽（柱 13 + 间隙 3）；x 起点 {@link #X0} ⇒ 柱区 8..152，右侧留白 8。 */
    public static final int SLOT_W = 16;
    public static final int BAR_W = 13;
    public static final int X0 = 8;

    // ── 柱区（纵向） ────────────────────────────────────────────
    /** 柱区顶（y=15）：上方 1..14 留给柱顶数字与溢出帽。 */
    public static final int PLOT_TOP = 15;
    /** 基线 y（y=55）：柱子从此向上长。 */
    public static final int BASE_Y = 55;
    /** 柱区高（40px）。 */
    public static final int PLOT_H = 40;
    /**
     * 柱高像素映射满程（24 块）—— = L4 中心列目标（{@link BeaconPyramid#targetAt(int, int)}）。
     * 契约测试 {@code maxHeightMatchesL4Center} 防漂移。
     */
    public static final int MAX_H = 24;
    /** 溢出帽高：{@code > MAX_H} 的柱不伸高，改柱顶 3px 金帽 + 绝对高度数字。 */
    public static final int CAP_H = 3;
    /** 底部读数行 y。 */
    public static final int LABEL_Y = 57;

    private BeaconPanelGeometry() {
    }

    /** 列 c 的柱位左 x。 */
    public static int columnX(int column) {
        return X0 + column * SLOT_W;
    }

    /** 柱顶 y（高度钳到 {@link #MAX_H}；0 高 ⇒ 基线）。 */
    public static int plotY(long height) {
        long clamped = Math.max(0, Math.min(height, MAX_H));
        return BASE_Y - (int) Math.round(clamped * PLOT_H / (double) MAX_H);
    }

    /** 目标刻度线 y（fill 排他 ⇒ 渲染器画 {@code fill(x, y-1, BAR_W, 1)}）。 */
    public static int targetY(int target) {
        return BASE_Y - (int) Math.round(Math.max(0, target) * PLOT_H / (double) MAX_H);
    }

    /**
     * 下一级（**0 基**：0 = L1）是否可达——所有 {@code target > 0} 的列都存在。
     *
     * <p>不可达 ⇒ 面板底部显示「宽度不足，信标内移」而<b>不是</b> deficit：
     * 「补 N 块就能亮」在永远达不到时是谎言（设计稿 §2.6.2 不可达兜底）。</p>
     */
    public static boolean reachable(int nextLevel0, boolean[] columnExists) {
        for (int c = 0; c < BeaconPyramid.COLUMNS; c++) {
            if (BeaconPyramid.targetAt(nextLevel0, c) > 0 && !existsAt(columnExists, c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 柱顶 y——**柱高钳到该列自己的目标**（而非全局满程 24）。
     *
     * <p>2026-10-16 用户实测反馈「为什么都是平行的、不是金字塔状」：柱高若按实际堆叠数
     * （clamp 24）绘制，玩家把 9 列堆成一样多时就是 9 根平顶柱，金字塔形状只存在于
     * 1px 刻度线上，扛不住视觉。改为钳到该列目标后，**实心柱本身就是金字塔的填充版**：
     * 达标列柱顶贴刻度线、超额列贴线 + 金帽、欠缺列凹下去 + 横纹 ⇒ 任何时刻看过去都是
     * 一座「在建中的金字塔」，缺哪列凹哪里。非参与列（target=0）不画柱（基线即顶）。</p>
     */
    public static int columnTopY(long height, int target) {
        if (target <= 0) {
            return BASE_Y;                    // 非参与列：不画柱
        }
        return plotY(Math.min(height, target));
    }

    /**
     * 未达标列的<b>目标值</b>数字（行动指令口径，非差额）；达标 / 超额 / 非参与列 ⇒ {@code -1}（不画）。
     *
     * <p>「数字从差额 −7 改为目标值 15」——玩家不心算加法；数字贴着刻度线，
     * 「目标标记」是线 + 数的整体（设计稿 §2.6.2 提示逻辑 ①）。</p>
     */
    public static int goalLabel(long height, int target) {
        return target > 0 && height < target ? target : -1;
    }

    /** 该列是否已超额（堆叠数 > 目标）⇒ 画金帽（贴刻度线上方，不伸高、不标数字）。 */
    public static boolean isOverTarget(long height, int target) {
        return target > 0 && height > target;
    }

    /**
     * 缺口区高度（像素；0 = 无缺口 / 非参与列）。横纹从<b>柱顶</b>画到<b>刻度</b>
     * （柱顶 y 更大 ⇒ 差为正）。
     */
    public static int gapHeight(long height, int target) {
        if (target <= 0 || height >= target) {
            return 0;
        }
        return plotY(height) - targetY(target);
    }

    /** 列存在性安全读取（null / 短数组 ⇒ false）。 */
    public static boolean existsAt(boolean[] columnExists, int column) {
        return columnExists != null && column >= 0 && column < columnExists.length
            && columnExists[column];
    }
}
