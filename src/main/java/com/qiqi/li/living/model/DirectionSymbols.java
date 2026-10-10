package com.qiqi.li.living.model;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * 方向符号的文本渲染入口 —— 让 8 个方向箭头走<b>同一个字体</b>。
 *
 * <h3>为什么需要它（2026-10-10 实测发现）</h3>
 * <p>原版点阵字体<b>只含 4 个基本方向箭头</b>（U+2190–U+2194：{@code ←↑→↓}），
 * <b>不含对角箭头</b>（U+2196–U+2199：{@code ↖↗↙↘}）—— 后者会落到<b>兜底字体</b>，
 * 风格与尺寸都与基本箭头不一致（表现为「斜向箭头小了一圈」）。</p>
 *
 * <h3>解法：模组自带一个字体</h3>
 * <p>{@code living_item:direction}（{@code assets/living_item/font/direction.json}）有两个 provider：</p>
 * <ol>
 *   <li><b>自绘点阵</b>（{@code textures/font/direction_arrows.png}）—— 补上 4 个对角箭头。
 *       ⚠️ 必须放<b>第一位</b>：字形查询取<b>第一个命中</b>的 provider。</li>
 *   <li>{@code reference: minecraft:default} —— 原样拿到全部原版字形（含空格宽度，
 *       故不要改成 {@code include/default}，那份缺 space provider）。</li>
 * </ol>
 * <p>把符号文本挂上该字体即可 —— <b>8 个方向符号风格统一，其它文字完全不受影响</b>。</p>
 *
 * <p>⚠️ 本类<b>不</b>改变 {@link Pos2D#getSymbol()}（它仍返回字符串，供日志 / 字符串拼接使用），
 * 只提供「带字体样式的文本组件」。调用方把组件作为 {@code Component.translatable(...)} 的参数传入即可。</p>
 */
public final class DirectionSymbols {

    /** 方向符号专用字体（见 {@code assets/living_item/font/direction.json}）。 */
    public static final ResourceLocation FONT =
        ResourceLocation.fromNamespaceAndPath(LivingMod.ID, "direction");

    private DirectionSymbols() {}

    /**
     * 把符号字符串（如 {@code "↖"}、{@code "—"}）包成带专用字体的文本组件。
     *
     * <p>非方向字符（{@code —} / {@code ?}）挂同一字体也无害 —— 它们由
     * {@code reference: minecraft:default} 原样解析。</p>
     */
    public static MutableComponent literal(String symbol) {
        return Component.literal(symbol).withStyle(Style.EMPTY.withFont(FONT));
    }

    /** 方向 → 带专用字体的文本组件。 */
    public static MutableComponent of(Pos2D direction) {
        return literal(direction.getSymbol());
    }
}
