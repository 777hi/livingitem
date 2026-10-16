package com.qiqi.li.client.render;

import com.qiqi.li.living.domain.beacon.BeaconPanelGeometry;
import com.qiqi.li.living.domain.beacon.BeaconPyramid;
import com.qiqi.li.living.domain.beacon.BeaconTooltipComponent;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/**
 * 活信标「9 柱小金字塔」tooltip 面板渲染器（设计稿 §2.6.2，Phase 4）。
 *
 * <p>纯绘制逻辑——所有坐标判定走 {@link BeaconPanelGeometry}（可单测），本类只画。
 * 面板<b>始终可见</b>（不依赖 F3+H；同活涂蜡发电机仪器面板口径，见
 * {@code LivingItemClient} 的 GatherComponents 注释先例）。</p>
 *
 * <p><b>元素编码</b>（每列独立，9 段阶梯刻度而非水平目标线——各列目标高度各异）：</p>
 * <ul>
 *   <li><b>实心柱</b> = 现状（中心列 = 信标列亮青，其余列暗青）；</li>
 *   <li><b>阶梯刻度线</b> = 该列自己的目标高度（琥珀；满级档金色），仅未达标列；</li>
 *   <li><b>横纹缺口</b> = 待填区（柱顶 → 刻度，3px 一条 1px 线）；</li>
 *   <li><b>金帽 + 数字</b> = 溢出（{@code >24}），数字 = 实际高度；</li>
 *   <li><b>1px 竖虚线</b> = 越界列（被容器边界钳制，别填了）——与「空槽 = 什么都不画」区分；</li>
 *   <li><b>数字</b> = 未达标列标<b>目标值</b>（行动指令口径，非差额）；达标列无数字。</li>
 * </ul>
 *
 * <p><b>底部双读数</b>：左「{@code to L3: 48 more} / {@code no room - move beacon} / {@code L4 full}」，
 * 右「{@code 51/83}（已投/本级总需求，含放错列的料 ⇒ 多投不动进度，玩家自己发现）/ {@code 164 stacked}」。</p>
 */
public class LivingBeaconTooltipRenderer implements ClientTooltipComponent {

    // ── 配色：沿用活涂蜡发电机仪器面板体系（深空蓝底 + 电光青 + 熔铜金）──
    private static final int BG_COLOR = 0xF00A0E1A;
    private static final int BORDER_COLOR = 0xFF2A2748;
    /** 主数据（中心列 = 信标列）：电光青。 */
    private static final int BAR_COLOR = 0xFF4CD9E8;
    /** 非中心列：暗青。 */
    private static final int BAR_DIM_COLOR = 0xFF2E7A88;
    /** 缺口横纹：暗青。 */
    private static final int GAP_COLOR = 0xFF1E4A56;
    /** 目标刻度线（下一级目标）：琥珀。 */
    private static final int TARGET_COLOR = 0xFFFFB454;
    /** 目标刻度线（满级 L4 行）：淡金。 */
    private static final int TARGET_FULL_COLOR = 0xFFFFE17A;
    /** 越界列竖虚线：近背景暗色。 */
    private static final int DEAD_COLOR = 0xFF2A2A38;
    /** 标签 / 读数文字：淡紫灰。 */
    private static final int LABEL_COLOR = 0xFF8786A8;
    /** 溢出帽与溢出数字：亮金。 */
    private static final int CAP_COLOR = 0xFFFFB454;
    /** 目标值数字：暗金（与刻度线呼应）。 */
    private static final int GOAL_NUM_COLOR = 0xFFD8A860;

    private final int level;
    private final long[] heights;
    private final boolean[] exists;

    public LivingBeaconTooltipRenderer(BeaconTooltipComponent component) {
        this.level = component.level();
        this.heights = component.columnHeights();
        this.exists = component.columnExists();
    }

    @Override
    public int getHeight() {
        return BeaconPanelGeometry.PANEL_H;
    }

    @Override
    public int getWidth(Font font) {
        return BeaconPanelGeometry.PANEL_W;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics g) {
        g.fill(x, y, x + BeaconPanelGeometry.PANEL_W, y + BeaconPanelGeometry.PANEL_H, BG_COLOR);
        g.fill(x, y, x + BeaconPanelGeometry.PANEL_W, y + 1, BORDER_COLOR);
        g.fill(x, y + BeaconPanelGeometry.PANEL_H - 1, x + BeaconPanelGeometry.PANEL_W,
            y + BeaconPanelGeometry.PANEL_H, BORDER_COLOR);
        g.fill(x, y, x + 1, y + BeaconPanelGeometry.PANEL_H, BORDER_COLOR);
        g.fill(x + BeaconPanelGeometry.PANEL_W - 1, y, x + BeaconPanelGeometry.PANEL_W,
            y + BeaconPanelGeometry.PANEL_H, BORDER_COLOR);

        boolean full = level >= BeaconPyramid.MAX_LEVEL;
        // 目标行：未满级画「下一级」目标；满级画 L4 行作满配参考
        int targetLevel0 = full ? BeaconPyramid.MAX_LEVEL - 1 : level;
        boolean reachable = BeaconPanelGeometry.reachable(targetLevel0, exists);

        int deficit = 0;
        long stacked = 0;
        for (int c = 0; c < BeaconPyramid.COLUMNS; c++) {
            int px = x + BeaconPanelGeometry.columnX(c);
            long h = heightAt(c);
            int t = BeaconPyramid.targetAt(targetLevel0, c);
            stacked += h;

            if (!BeaconPanelGeometry.existsAt(exists, c)) {
                // 越界列：柱位中央 1px 竖虚线（贯穿柱区）——「这列不存在，别填了」
                g.fill(px + BeaconPanelGeometry.BAR_W / 2, y + BeaconPanelGeometry.PLOT_TOP,
                    1, BeaconPanelGeometry.BASE_Y - BeaconPanelGeometry.PLOT_TOP, DEAD_COLOR);
                continue;
            }
            if (t > 0 && h < t) {
                deficit += t - h;
            }
            if (t > 0) {
                g.fill(px, y + BeaconPanelGeometry.targetY(t) - 1, BeaconPanelGeometry.BAR_W, 1,
                    full ? TARGET_FULL_COLOR : TARGET_COLOR);
            }
            int gap = BeaconPanelGeometry.gapHeight(h, t);
            if (gap > 0) {
                // 横纹从柱顶画到刻度（y 从 targetY 递增到 plotY）
                int top = BeaconPanelGeometry.plotY(h);
                for (int yy = BeaconPanelGeometry.targetY(t); yy < top; yy += 3) {
                    g.fill(px, y + yy, BeaconPanelGeometry.BAR_W, 1, GAP_COLOR);
                }
            }
            if (h > 0) {
                int top = BeaconPanelGeometry.plotY(h);
                g.fill(px, y + top, BeaconPanelGeometry.BAR_W,
                    BeaconPanelGeometry.BASE_Y - top,
                    c == BeaconPyramid.CENTER ? BAR_COLOR : BAR_DIM_COLOR);
            }
            if (BeaconPanelGeometry.isOverflow(h)) {
                g.fill(px, y + BeaconPanelGeometry.PLOT_TOP - BeaconPanelGeometry.CAP_H,
                    BeaconPanelGeometry.BAR_W, BeaconPanelGeometry.CAP_H, CAP_COLOR);
            }
            drawColumnLabel(font, g, x, y, px, h, t);
        }

        // ── 底部双读数 ──────────────────────────────────────────
        Component left;
        if (!reachable) {
            left = Component.translatable("tooltip.livingitem.beacon.panel.no_room");
        } else if (full) {
            left = Component.translatable("tooltip.livingitem.beacon.panel.full");
        } else {
            left = Component.translatable("tooltip.livingitem.beacon.panel.to_next",
                level + 1, deficit);
        }
        Component right = full
            ? Component.translatable("tooltip.livingitem.beacon.panel.stacked", (int) stacked)
            : Component.literal(stacked + "/" + BeaconPyramid.totalForLevel(level + 1));

        g.drawString(font, left, x + BeaconPanelGeometry.X0,
            y + BeaconPanelGeometry.LABEL_Y, LABEL_COLOR, false);
        g.drawString(font, right,
            x + BeaconPanelGeometry.PANEL_W - 8 - font.width(right),
            y + BeaconPanelGeometry.LABEL_Y, LABEL_COLOR, false);
    }

    /**
     * 柱顶数字：溢出列标绝对高度（金帽上方），未达标列标<b>目标值</b>（刻度线上方）；
     * 达标列与非参与列不画——「干净」本身就是「没问题」的表达。
     */
    private void drawColumnLabel(Font font, GuiGraphics g, int panelX, int panelY,
                                 int px, long height, int target) {
        long overflow = BeaconPanelGeometry.overflowLabel(height);
        int goal = BeaconPanelGeometry.goalLabel(height, target);
        Component label;
        int color;
        int numTop;
        if (overflow >= 0) {
            label = Component.literal(String.valueOf(overflow));
            color = CAP_COLOR;
            numTop = BeaconPanelGeometry.PLOT_TOP - BeaconPanelGeometry.CAP_H;
        } else if (goal > 0) {
            label = Component.literal(String.valueOf(goal));
            color = GOAL_NUM_COLOR;
            numTop = BeaconPanelGeometry.targetY(target);
        } else {
            return;
        }
        g.drawString(font, label,
            px + BeaconPanelGeometry.BAR_W / 2 - font.width(label) / 2,
            panelY + numTop - 9, color, false);
    }

    private long heightAt(int column) {
        return column >= 0 && column < heights.length ? heights[column] : 0L;
    }
}
