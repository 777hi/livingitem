package com.qiqi.li.client.render;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.util.FastColor;

/**
 * 光束颜色分段（照原版 {@code BeaconBlockEntity.tick} 的扫描算法）—— 纯逻辑，可单测。
 *
 * <h3>原版机制（2026-10-16 核源码）</h3>
 * 从信标往上扫，每格取 {@code BlockState#getBeaconColorMultiplier}（染色玻璃 / 玻璃板 /
 * 信标自身都实现了 {@code BeaconBeamBlock}）：
 * <ul>
 *   <li><b>第 1 个有色块</b>（原版里是<b>信标自身</b>，色 = 白）⇒ 纯色新段；</li>
 *   <li><b>第 2 个有色块</b>（紧邻的第一块玻璃）⇒ 仍是<b>纯色</b>新段 —— 所以"直接放一块玻璃"
 *       拿到的是玻璃本色；</li>
 *   <li><b>第 3 个起</b>：同色 ⇒ 当前段加高；<b>异色 ⇒ 新段 = 前段色与新色的平均</b>
 *       （所以红+蓝叠出来是偏暗的混合色，不是清晰的两段）；</li>
 *   <li>非染色方块（空气等）⇒ 当前段加高；<b>不透明方块</b> ⇒ 停止扫描（后面的颜色不算）。</li>
 * </ul>
 * 渲染端"最后一段一直画到顶"（原版写死 1024），所以<b>感知上</b>就是"第一块玻璃决定整根颜色"。
 *
 * <h3>我们的对应</h3>
 * 活信标没有"信标自身那一格"，故以<b>白段起步、高度 0</b>：
 * 起点上方若是空气 ⇒ 白段加高（原版同款"白底"）；第一块玻璃 ⇒ 纯色新段；再往上同原版。
 * 全程没玻璃 ⇒ 单段白（原版此情形无光束；我们的光束本就常亮，故保留白色）。
 */
public final class BeamColorSections {

    /** 一段光束：颜色（ARGB）+ 该段高度（格）。 */
    public record Section(int color, int height) {
    }

    /** 扫描到的单格观测：颜色（{@code null} = 非染色方块）+ 是否不透明（截断扫描）。 */
    public record Observation(@Nullable Integer color, boolean opaque) {
    }

    private BeamColorSections() {
    }

    /**
     * 把"起点往上逐格的观测"折成分段列表（见类注释的原版规则）。
     *
     * @param upward       从起点上方第一格开始的逐格观测（遇到不透明块应已由调用方截断）
     * @param defaultColor 全程无染色方块时的颜色（{@code BeaconGlassColor.DEFAULT}）
     * @return 分段列表（高度之和 = 观测格数；最后一段由调用方延伸到光束终点）
     */
    public static List<Section> build(List<Observation> upward, int defaultColor) {
        if (upward.isEmpty()) {
            return List.of(new Section(defaultColor, 0));
        }

        List<Section> sections = new ArrayList<>();
        sections.add(new Section(defaultColor, 0));   // 白底段（高度 0，见类注释）
        int index = 0;

        for (Observation obs : upward) {
            if (obs.color() != null) {
                if (sections.size() == 1) {
                    // 第一块染色方块 ⇒ 纯色新段（原版"第 2 个有色块"分支）
                    sections.add(new Section(obs.color(), 1));
                    index = 1;
                } else if (obs.color() == sections.get(index).color()) {
                    sections.set(index, grow(sections.get(index)));
                } else {
                    // 异色 ⇒ 新段 = 前段色与新色的平均（原版 FastColor.ARGB32.average）
                    sections.add(new Section(
                        FastColor.ARGB32.average(sections.get(index).color(), obs.color()), 1));
                    index = sections.size() - 1;
                }
            } else {
                if (obs.opaque()) {
                    break;                            // 不透明 ⇒ 截断（后面的颜色不算）
                }
                sections.set(index, grow(sections.get(index)));
            }
        }

        if (sections.size() == 1) {
            // 全程无玻璃 ⇒ 单段默认色，高度 = 观测格数
            return List.of(new Section(defaultColor, upward.size()));
        }
        return List.copyOf(sections);
    }

    private static Section grow(Section section) {
        return new Section(section.color(), section.height() + 1);
    }
}
