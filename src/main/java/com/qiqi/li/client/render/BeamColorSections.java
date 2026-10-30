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
 *   <li>非染色方块（空气等）⇒ 当前段加高；<b>不透明方块</b> ⇒ 停止扫描（后面的颜色不算）。
 *       ⚠️ 截断实际由<b>调用方</b>负责（{@code LivingBeamRenderer} 扫描时就按
 *       {@code lightBlock >= 15 && !bedrock} 停）——本类的 {@code obs.opaque()} 只在
 *       无色分支生效；染色方块本身都是半透 ⇒ 原版不存在「有色且不透明」的方块，
 *       该分支差异不可达（2026-10-18 审查记录）。</li>
 * </ul>
 * 渲染端"最后一段一直画到顶"（原版写死 1024），所以<b>感知上</b>就是"第一块玻璃决定整根颜色"。
 *
 * <h3>我们的对应</h3>
 * 活信标<b>没有「信标自身那一格」</b>（光束起点就是箱口顶面 / 头颅顶面，那一格不是信标方块）
 * ⇒ 白底段高度 **0**（不占格）：起点上方第一格就是玻璃 ⇒ 玻璃段从底部第一格起画。
 * ⚠️ 原版该段是 1 格高（信标格自身 = 白）⇒ 原版「底部 1 格白」有实体依据，我们是
 * **有意偏离**：2026-10-18 用户实测「改变光束颜色，第一格不变色」——那格白没有对应物，
 * 第一格就该是玻璃色。
 * ⚠️ **白段 0 高 ⇒ 渲染端必须跳过而非终止**（{@code LivingBeamRenderer.renderBeamAlong}
 * 的 0 高段 continue + remain ≤ 0 才 break；2026-10-16 用户实测「贴着放玻璃整条消失」后定型）。
 */
public final class BeamColorSections {

    /** 全程无染色方块时的颜色 —— 不透明 ARGB 白色，内芯顶点会直接读取 alpha。 */
    public static final int DEFAULT_COLOR = 0xFFFFFFFF;

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
     * @param defaultColor 全程无染色方块时的颜色（{@link #DEFAULT_COLOR}）
     * @return 分段列表；<b>高度之和 = 观测格数</b>（首段白底高度 <b>0</b>——活信标没有
     *         「信标自身那一格」，见类注释；最后一段由调用方延伸到光束终点）。
     *         ⚠️ 2026-10-18 审查订正：此前写「观测格数 **+ 1**」，与实现（0 高白段）和
     *         类注释相反。
     */
    public static List<Section> build(List<Observation> upward, int defaultColor) {
        if (upward.isEmpty()) {
            return List.of(new Section(defaultColor, 0));
        }

        List<Section> sections = new ArrayList<>();
        sections.add(new Section(defaultColor, 0));   // 白底段 0 高：活信标没有信标自身那一格（见类注释）
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
