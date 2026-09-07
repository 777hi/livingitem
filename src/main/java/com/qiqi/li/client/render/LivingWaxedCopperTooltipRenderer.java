package com.qiqi.li.client.render;

import java.util.List;
import com.qiqi.li.living.domain.power.LivingWaxedCopperTooltipComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/**
 * 活涂蜡发电机的「仪器面板」tooltip 渲染器（v19.2 相位圆盘）。
 *
 * <p>三块内容，全部取真实相位数据：</p>
 * <ul>
 *   <li><strong>相位圆盘</strong>（左上）：一周 = 一个周期 P，每路相位一根
 *       <b>辐条</b>——角度 = 偏移 φᵢ，长度 = √|Δᵢ|；外圈琥珀弧 = 解锁度 u；
 *       12 点方向的小方点标记 φ=0。辐条不是箭头：合因子用的是标量求和
 *       Σ√|Δᵢ|，相位只参与去重计数 n，不做矢量相加，画成箭头会误导。</li>
 *   <li><strong>锈级横向条形图</strong>（右上）：四个锈蚟级的基础出力，四种铜锈色，
 *       按原柱状图逆时针旋转 90° 展开；一眼看出哪一级是共振短板。</li>
 *   <li><strong>相位展开条</strong>（下方全宽）：把圆盘剪开拉直——横轴 = [0, P)，
 *       每路相位一根柱子，柱高 = √|Δᵢ|。<b>柱高之和 = Σ√|Δᵢ|</b>，也就是合因子的底数，
 *       于是「多加一根柱子（凑相位）」与「把柱子长高（加大 Δ）」两个优化动作在图上直接可见。</li>
 * </ul>
 *
 * <p>相比旧版示波器（n 条均匀错开、等幅、固定周期数的装饰性正弦，与真实信号无任何数据连接），
 * 圆盘把相位编码成角度而非横向平移：n 条辐条各自占用独立的角度扇区，
 * 不会像 n 条叠在同一条基线上的波形那样糊成一团。</p>
 *
 * <p>面板只在 F3+H 高级模式出现（由
 * {@code LivingItemClient} 的 GatherComponents 事件决定是否挂载），
 * 这里是纯绘制逻辑，不再二次判空——未挂载时本类根本不会被实例化。</p>
 */
public class LivingWaxedCopperTooltipRenderer implements ClientTooltipComponent {

    private static final int PANEL_W = 172;
    private static final int PANEL_H = 112;

    private static final int BG_COLOR = 0xF00A0A14;
    private static final int BORDER_COLOR = 0xFF3A2A5A;
    private static final int GRID_COLOR = 0xFF20203A;
    private static final int TRACE_COLOR = 0xFF55FFFF;
    private static final int LABEL_COLOR = 0xFF8A8A9A;
    /** 解锁度外弧（琥珀） */
    private static final int UNLOCK_COLOR = 0xFFBA7517;

    /** 四种锈蚀等级的铜色：未锈 / 暴露 / 风化 / 氧化 */
    private static final int[] LEVEL_COLORS = {0xFFE08A4B, 0xFFC07050, 0xFF6FA287, 0xFF4AA8A8};

    // ── 相位圆盘（左上）── 圆心偏移基于面板左上角
    private static final int WHEEL_CX = 41;
    private static final int WHEEL_CY = 40;
    /** 周期圆环半径 */
    private static final int WHEEL_RING_R = 31;
    /** 解锁度外弧半径 */
    private static final int WHEEL_ARC_R = 36;
    /** 辐条最大半径（对应最大 √|Δ|） */
    private static final int WHEEL_SPOKE_R = 28;
    /** 中心毂半径（辐条内端起点） */
    private static final int WHEEL_HUB_R = 8;

    // ── 锈级横向条形图（右上）──
    // 右侧为基线，条形向左增长；等级 3 在右上、等级 0 在右下，
    // 视觉上按锈蚀程度从上到下排列（氧化 → 风化 → 暴露 → 新鲜）。
    /** 横条右侧基线（面板内 x=164，向左增长至 x=94） */
    private static final int LEVEL_BASE_X = 164;
    private static final int LEVEL_Y = 9;
    private static final int LEVEL_BAR_H = 13;
    private static final int LEVEL_GAP = 4;
    private static final int LEVEL_MAX_W = 70;

    // ── 相位展开条（下方全宽）──
    private static final int STEM_X = 5;
    private static final int STEM_W = 162;
    private static final int STEM_BASE_Y = 100;
    private static final int STEM_H = 18;

    private final int phaseCount;
    private final int period;
    private final List<Long> levelPower;
    private final int activeLevels;
    private final int unlockPermille;
    private final List<Integer> phaseOffsets;
    private final List<Integer> phaseDeltas;
    private final int minPhaseGap;
    private final boolean empty;

    public LivingWaxedCopperTooltipRenderer(LivingWaxedCopperTooltipComponent component) {
        this.phaseCount = component.phaseCount();
        this.period = component.period();
        this.levelPower = component.levelPowerMilliFe();
        this.activeLevels = component.activeLevels();
        this.unlockPermille = component.unlockPermille();
        this.phaseOffsets = component.phaseOffsets();
        this.phaseDeltas = component.phaseDeltas();
        this.minPhaseGap = component.minPhaseGap();
        // 无信号（周期未检出）且无锈级出力时，面板没有可显示内容
        this.empty = period <= 0 && maxOf(levelPower) <= 0;
    }

    @Override
    public int getHeight() {
        return empty ? 0 : PANEL_H;
    }

    @Override
    public int getWidth(Font font) {
        return empty ? 0 : PANEL_W;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
        if (empty) return;

        guiGraphics.fill(x, y, x + PANEL_W, y + PANEL_H, BG_COLOR);
        guiGraphics.fill(x, y, x + PANEL_W, y + 1, BORDER_COLOR);
        guiGraphics.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, BORDER_COLOR);
        guiGraphics.fill(x, y, x + 1, y + PANEL_H, BORDER_COLOR);
        guiGraphics.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, BORDER_COLOR);

        renderWheel(guiGraphics, x, y);
        renderLevelBars(guiGraphics, x, y);
        renderStemPlot(guiGraphics, x, y);

        String scopeLabel = "P=" + period + "t n=" + phaseCount
            + (minPhaseGap >= 0 ? " Δφ=" + minPhaseGap + "t" : "");
        Component scopeText = Component.literal(scopeLabel);
        Component levelText = Component.literal("锈级 " + activeLevels + "/4");

        // 锈级读数右对齐：长周期（P 上千）时左侧读数会变长，固定 x 会让两块文字撞上
        int labelY = y + PANEL_H - 9;
        int levelX = x + PANEL_W - 5 - font.width(levelText);

        // 仍然撞上就牺牲 Δφ——它是圆盘读不精确时的兜底，F3+H 域快照文本行里同样有
        if (minPhaseGap >= 0 && x + STEM_X + font.width(scopeText) > levelX - 4) {
            scopeText = Component.literal("P=" + period + "t n=" + phaseCount);
        }

        guiGraphics.drawString(font, scopeText, x + STEM_X, labelY, LABEL_COLOR, false);
        guiGraphics.drawString(font, levelText, levelX, labelY, LABEL_COLOR, false);
    }

    /**
     * 相位圆盘：一周 = 一个周期 P，12 点为 φ=0，顺时针增大。
     * 辐条角度 = 2π·φᵢ/P，长度 ∝ √|Δᵢ|；外弧按解锁度 u 填充。
     */
    private void renderWheel(GuiGraphics g, int x, int y) {
        int cx = x + WHEEL_CX;
        int cy = y + WHEEL_CY;

        drawRing(g, cx, cy, WHEEL_RING_R, GRID_COLOR);

        if (period <= 0 || phaseCount <= 0) {
            // 无信号：只留空环 + 中心毂，保持仪器面板观感
            drawRing(g, cx, cy, WHEEL_HUB_R, GRID_COLOR);
            return;
        }

        if (unlockPermille > 0) {
            drawArc(g, cx, cy, WHEEL_ARC_R, Math.min(1.0, unlockPermille / 1000.0), UNLOCK_COLOR);
        }

        // φ=0 参考点：12 点方向的小方点
        g.fill(cx - 1, cy - WHEEL_RING_R - 1, cx + 2, cy - WHEEL_RING_R + 2, LABEL_COLOR);

        int maxDelta = 0;
        for (int d : phaseDeltas) {
            if (d > maxDelta) maxDelta = d;
        }

        int n = Math.min(phaseOffsets.size(), phaseDeltas.size());
        for (int i = 0; i < n; i++) {
            double amp = maxDelta > 0
                ? Math.sqrt(phaseDeltas.get(i)) / Math.sqrt(maxDelta)
                : 1.0;
            int r = WHEEL_HUB_R + (int) Math.round(amp * (WHEEL_SPOKE_R - WHEEL_HUB_R));
            r = Math.max(WHEEL_HUB_R + 2, r);   // 极弱相位也留一点可见长度

            double a = 2.0 * Math.PI * phaseOffsets.get(i) / period;
            double sin = Math.sin(a);
            double cos = Math.cos(a);
            drawLine(g,
                cx + (int) Math.round(WHEEL_HUB_R * sin), cy - (int) Math.round(WHEEL_HUB_R * cos),
                cx + (int) Math.round(r * sin), cy - (int) Math.round(r * cos),
                TRACE_COLOR);
        }

        drawRing(g, cx, cy, WHEEL_HUB_R, GRID_COLOR);
    }

    /**
     * 相位展开条：圆盘的直角坐标版本——横轴 [0, P)，每路相位一根柱子，柱高 ∝ √|Δᵢ|。
     * 柱高之和 = Σ√|Δᵢ|，柱子的横坐标 = 该路在周期里哪一步跳变（对应跳变门控的记账口径）。
     */
    private void renderStemPlot(GuiGraphics g, int x, int y) {
        int left = x + STEM_X;
        int base = y + STEM_BASE_Y;
        g.fill(left, base, left + STEM_W, base + 1, GRID_COLOR);

        if (period <= 0) return;
        int n = Math.min(phaseOffsets.size(), phaseDeltas.size());
        if (n <= 0) return;

        int maxDelta = 0;
        for (int i = 0; i < n; i++) {
            if (phaseDeltas.get(i) > maxDelta) maxDelta = phaseDeltas.get(i);
        }

        double slotW = (double) STEM_W / period;
        int barW = slotW >= 3.0 ? (int) slotW - 1 : 1;
        for (int i = 0; i < n; i++) {
            double amp = maxDelta > 0
                ? Math.sqrt(phaseDeltas.get(i)) / Math.sqrt(maxDelta)
                : 1.0;
            int h = Math.max(2, (int) Math.round(amp * STEM_H));
            int bx = left + (int) Math.round(phaseOffsets.get(i) * slotW);
            if (bx + barW > left + STEM_W) bx = left + STEM_W - barW;
            g.fill(bx, base - h, bx + barW, base, TRACE_COLOR);
        }
    }

    /**
     * 锈级横向条形图：四锈级出力，用四种锈蚀铜色。
     *
     * <p>等价于旧竖直图逆时针旋转 90°：旧图的「基线在下、等级从左到右」
     * 变成「基线在右、等级从上到下」，条形向左增长。条长仍是相对出力，短条代表共振短板。</p>
     */
    private void renderLevelBars(GuiGraphics g, int x, int y) {
        long maxV = maxOf(levelPower);
        if (maxV <= 0) return;

        int baseX = x + LEVEL_BASE_X;
        for (int i = 0; i < levelPower.size() && i < LEVEL_COLORS.length; i++) {
            long v = levelPower.get(i);
            if (v <= 0) continue;
            int w = Math.max(2, (int) Math.round(v * (double) (LEVEL_MAX_W - 2) / maxV));
            // 反转垂直排列：氧化级 3 在最上方，未锈级 0 在最下方。
            int row = LEVEL_COLORS.length - 1 - i;
            int by = y + LEVEL_Y + row * (LEVEL_BAR_H + LEVEL_GAP);
            // 基线在右侧：条形从 baseX 向左延伸
            g.fill(baseX - w, by, baseX, by + LEVEL_BAR_H, LEVEL_COLORS[i]);
        }
    }

    // ── 逐像素图元（GuiGraphics 只有矩形 fill，圆与斜线需自己走点）──

    /** 中点画圆（8 分对称），1px 描边 */
    private static void drawRing(GuiGraphics g, int cx, int cy, int r, int color) {
        int px = r;
        int py = 0;
        int err = 1 - r;
        while (px >= py) {
            plot8(g, cx, cy, px, py, color);
            py++;
            if (err < 0) {
                err += 2 * py + 1;
            } else {
                px--;
                err += 2 * (py - px) + 1;
            }
        }
    }

    private static void plot8(GuiGraphics g, int cx, int cy, int x, int y, int color) {
        g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
        g.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
        g.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
        g.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
        g.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
        g.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
        g.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
        g.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);
    }

    /** 从 12 点起顺时针画一段圆弧；turns = 圈数（0..1），线宽 3px */
    private static void drawArc(GuiGraphics g, int cx, int cy, int r, double turns, int color) {
        if (turns <= 0) return;
        int steps = Math.max(1, (int) Math.ceil(2 * Math.PI * r * turns));
        for (int i = 0; i <= steps; i++) {
            double a = 2.0 * Math.PI * turns * i / steps;
            int px = cx + (int) Math.round(r * Math.sin(a));
            int py = cy - (int) Math.round(r * Math.cos(a));
            g.fill(px - 1, py - 1, px + 2, py + 2, color);
        }
    }

    /** Bresenham 直线 */
    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (true) {
            g.fill(x, y, x + 1, y + 1, color);
            if (x == x1 && y == y1) break;
            int e2 = err * 2;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static long maxOf(List<Long> values) {
        long m = 0;
        for (long v : values) m = Math.max(m, v);
        return m;
    }
}
