package com.qiqi.li.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

import org.jetbrains.annotations.Nullable;

/**
 * 通用图标进度条装饰器 —— 把「某个比例」画成原版耐久条同款的小条。
 *
 * <h3>为什么是耐久条的形态</h3>
 * <p>耐久条是 MC 里<b>唯一长在图标上的进度语言</b>（快捷栏 / 背包 / 任何容器全覆盖），
 * 玩家对它已有条件反射。本装饰器复刻它的几何（13×2 黑底 + 13×1 彩条，左下角），
 * 只把「比例」与「颜色」交给调用方定义 ⇒ 活物品的资源量（燃料 / 能量 / 液体）都能用。</p>
 *
 * <h3>与耐久条的关系：各画各的，不打架</h3>
 * <p>刻意<b>不</b>去覆盖 {@code Item#getBarWidth / getBarColor}（那会与真耐久抢同一个语义槽，
 * 且 tooltip 的耐久文本读同一套数据）。本装饰器自绘，位置可换、颜色独立；
 * 活物品一般无损（耐久条槽位空着）⇒ 直接用原版位置是零学习成本的选择。</p>
 *
 * <h3>数据来源约束（重要）</h3>
 * <p>装饰器接口只有 {@code (stack, x, y)}，<b>拿不到槽位索引</b> ⇒ 只能读「跟着物品走」的数据：
 * <b>数据组件</b>（燃料次数 / 能量 / 流体体积都是落盘的）✅；
 * 运行时缓存里只发给 viewer 的瞬态量（酿造倒计时、漏斗冷却）❌——那些归 tooltip 与白幕。</p>
 *
 * <h3>朝向</h3>
 * <p>横条 = 原版耐久条位置（左下，从左往右长）；竖条 = 左缘（从底往上长，像液位/温度计）。
 * 两者几何都由纯函数算出，可单测。竖条用于「液位」语义（如活水源），当前尚无接入方。</p>
 *
 * <h3>z 语义</h3>
 * <p>相对槽位 pose 抬 190：高于物品模型（150），低于白幕（200）⇒ 白幕播放时盖在条上（白幕是横幅）。</p>
 */
public class ProgressBarDecorator implements IItemDecorator {

    /** 相对槽位 pose 的抬升量（低于白幕的 200 ⇒ 白幕盖在条上） */
    private static final int BAR_Z = 190;

    /** 原版耐久条的黑底（不透明黑） */
    private static final int TRACK_COLOR = 0xFF000000;

    /** 条的长度（对齐原版耐久条 13px） */
    private static final int LENGTH = 13;

    /** 条几何朝向 */
    public enum Orientation {
        /** 左下角横条，从左往右长（原版耐久条位置） */
        HORIZONTAL,
        /** 左缘竖条，从底往上长（液位语义） */
        VERTICAL
    }

    /**
     * 一条待画的进度。
     *
     * @param fraction    填充比例（0..1；≤0 不画，&gt;1 截断）
     * @param color       ARGB 颜色（alpha 会被强制为 FF）
     * @param orientation 朝向
     */
    public record Bar(float fraction, int color, Orientation orientation) {

        public static Bar horizontal(float fraction, int color) {
            return new Bar(fraction, color, Orientation.HORIZONTAL);
        }

        public static Bar vertical(float fraction, int color) {
            return new Bar(fraction, color, Orientation.VERTICAL);
        }
    }

    /**
     * 比例供给函数 —— 由各活物品域提供。
     *
     * <p>返回 {@code null} 表示「这个栈现在没有可画的量」（非活物品 / 量为 0）⇒ 不画。
     * 装饰器不做任何域判断，域自己决定「什么时候值得显示一条」。</p>
     */
    @FunctionalInterface
    public interface BarSpec {
        @Nullable Bar resolve(ItemStack stack);
    }

    private final BarSpec spec;

    public ProgressBarDecorator(BarSpec spec) {
        this.spec = spec;
    }

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        Bar bar = spec.resolve(stack);
        if (!shouldDraw(bar)) return false;

        float f = Mth.clamp(bar.fraction(), 0.0F, 1.0F);
        int[] track = trackRect(bar.orientation());
        int[] fill = fillRect(bar.orientation(), f);

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(xOffset, yOffset, BAR_Z);
        guiGraphics.fill(RenderType.guiOverlay(), track[0], track[1], track[2], track[3], TRACK_COLOR);
        guiGraphics.fill(RenderType.guiOverlay(), fill[0], fill[1], fill[2], fill[3], bar.color() | 0xFF000000);
        pose.popPose();
        return true;
    }

    /** 该不该画（纯判据，包级可见供单测）：有规格且比例 &gt; 0。 */
    static boolean shouldDraw(@Nullable Bar bar) {
        return bar != null && bar.fraction() > 0.0F;
    }

    /**
     * 黑底矩形（相对图标左上角，{@code [x1, y1, x2, y2]}，右下为开区间）。
     *
     * <p>横 = 原版耐久条黑底 {@code (2, 13, 15, 15)}；竖 = 左缘 {@code (1, 2, 3, 15)}。</p>
     */
    static int[] trackRect(Orientation orientation) {
        return orientation == Orientation.HORIZONTAL
            ? new int[] {2, 13, 2 + LENGTH, 13 + 2}
            : new int[] {1, 2, 1 + 2, 15};
    }

    /**
     * 彩条矩形（相对图标左上角）。
     *
     * <p>横：从左往右长，宽 = {@code round(13 × f)}；竖：从底往上长，高 = {@code round(13 × f)}。
     * 比例与长度按原版用 {@code Math.round}（{@code Item#getBarWidth} 同款取整）；
     * 内部自带 clamp ⇒ 纯函数单独调用也健全（render 侧另有一次 clamp）。</p>
     */
    static int[] fillRect(Orientation orientation, float fraction) {
        int len = Math.round(LENGTH * Mth.clamp(fraction, 0.0F, 1.0F));
        if (orientation == Orientation.HORIZONTAL) {
            return new int[] {2, 13, 2 + len, 14};
        }
        return new int[] {1, 15 - len, 3, 15};
    }
}
