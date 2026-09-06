package com.qiqi.li.client.render;

import java.util.List;
import com.qiqi.li.living.domain.power.LivingWaxedCopperTooltipComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/**
 * 活涂蜡发电机的「仪器面板」tooltip 渲染器（§3.6.1 可视化）。
 *
 * <p>左半区是<strong>相位示波器</strong>：按相数 n 画出 n 条相位错开的正弦波（覆盖约 2 个周期），
 * 反映这台发电机看到的多相交变信号形状；右半区是<strong>锈级柱状图</strong>：
 * 四个锈蚟级的基础出力 EMA，用四种铜锈色区分，一眼看出哪一级是共振短板。</p>
 *
 * <p>面板只在 F3+H 高级模式出现（由
 * {@code LivingItemClient} 的 GatherComponents 事件决定是否挂载），
 * 这里是纯绘制逻辑，不再二次判空——未挂载时本类根本不会被实例化。</p>
 */
public class LivingWaxedCopperTooltipRenderer implements ClientTooltipComponent {

    private static final int PANEL_W = 172;
    private static final int PANEL_H = 62;

    private static final int BG_COLOR = 0xF00A0A14;
    private static final int BORDER_COLOR = 0xFF3A2A5A;
    private static final int GRID_COLOR = 0xFF20203A;
    private static final int TRACE_COLOR = 0xFF55FFFF;
    private static final int LABEL_COLOR = 0xFF8A8A9A;

    /** 四种锈蚀等级的铜色：未锈 / 暴露 / 风化 / 氧化 */
    private static final int[] LEVEL_COLORS = {0xFFE08A4B, 0xFFC07050, 0xFF6FA287, 0xFF4AA8A8};

    /** 多相波形的相位配色：相 0 高亮青，其余逐级变暗，便于区分各相 */
    private static final int[] PHASE_COLORS = {
        0xFF55FFFF, 0xFF3FBFBF, 0xFF2E8C8C, 0xFF206060, 0xFF184848, 0xFF123838
    };

    private static final int SCOPE_X = 5;
    private static final int SCOPE_W = 102;
    private static final int SCOPE_Y = 5;
    private static final int SCOPE_H = 40;

    private static final int BAR_X = 117;
    private static final int BAR_W = 10;
    private static final int BAR_GAP = 3;

    /** 示波器横轴显示的周期数（让波形看起来像「波」而非单段） */
    private static final int SCOPE_CYCLES = 2;

    private final int phaseCount;
    private final int period;
    private final List<Long> levelPower;
    private final int activeLevels;
    private final boolean empty;

    public LivingWaxedCopperTooltipRenderer(LivingWaxedCopperTooltipComponent component) {
        this.phaseCount = component.phaseCount();
        this.period = component.period();
        this.levelPower = component.levelPowerMilliFe();
        this.activeLevels = component.activeLevels();
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

        renderScope(guiGraphics, x, y);
        renderLevelBars(guiGraphics, x, y);

        String scopeLabel = "相位 P=" + period + "t n=" + phaseCount;
        guiGraphics.drawString(font, Component.literal(scopeLabel),
            x + SCOPE_X, y + PANEL_H - 11, LABEL_COLOR, false);
        guiGraphics.drawString(font, Component.literal("锈级 " + activeLevels + "/4"),
            x + BAR_X - 2, y + PANEL_H - 11, LABEL_COLOR, false);
    }

    /** 左半区：多相交变波形（相数 n → n 条相位错开的正弦，覆盖约 2 个周期） */
    private void renderScope(GuiGraphics g, int x, int y) {
        int left = x + SCOPE_X;
        int top = y + SCOPE_Y;
        int midY = top + SCOPE_H / 2;

        // 水平刻度网格（含中线），提供示波器参考线
        for (int i = 0; i <= 4; i++) {
            int gy = top + SCOPE_H * i / 4;
            g.fill(left, gy, left + SCOPE_W, gy + 1, GRID_COLOR);
        }

        if (period <= 0 || phaseCount <= 0) {
            // 无信号：画一条平基线，保持仪器面板观感
            g.fill(left, midY, left + SCOPE_W, midY + 1, GRID_COLOR);
            return;
        }

        int amp = SCOPE_H / 2 - 2;
        int n = phaseCount;
        int samples = SCOPE_W;
        for (int ph = 0; ph < n; ph++) {
            double phaseShift = 2.0 * Math.PI * ph / n;
            int color = PHASE_COLORS[Math.min(ph, PHASE_COLORS.length - 1)];
            for (int px = 0; px < samples; px++) {
                double t = (double) px / samples * SCOPE_CYCLES * 2.0 * Math.PI;
                double v = Math.sin(t + phaseShift);
                int yy = midY - (int) Math.round(v * amp);
                g.fill(left + px, yy, left + px + 1, yy + 1, color);
            }
        }
    }

    /** 右半区：四锈级出力柱状图（用四种锈蚀铜色） */
    private void renderLevelBars(GuiGraphics g, int x, int y) {
        int baseline = y + SCOPE_Y + SCOPE_H;
        long maxV = maxOf(levelPower);
        if (maxV <= 0) return;

        for (int i = 0; i < levelPower.size() && i < LEVEL_COLORS.length; i++) {
            long v = levelPower.get(i);
            if (v <= 0) continue;
            int h = Math.max(2, (int) Math.round(v * (double) (SCOPE_H - 2) / maxV));
            int bx = x + BAR_X + i * (BAR_W + BAR_GAP);
            g.fill(bx, baseline - h, bx + BAR_W, baseline, LEVEL_COLORS[i]);
        }
    }

    private static long maxOf(List<Long> values) {
        long m = 0;
        for (long v : values) m = Math.max(m, v);
        return m;
    }
}
