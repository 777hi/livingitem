package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;

/**
 * 光束颜色分段与原版渲染顶点的 ARGB 契约守卫。
 */
class BeamColorSectionsTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private static BeamColorSections.Observation glass(int color) {
        return new BeamColorSections.Observation(color, false);
    }

    private static BeamColorSections.Observation air() {
        return new BeamColorSections.Observation(null, false);
    }

    private static BeamColorSections.Observation opaque() {
        return new BeamColorSections.Observation(null, true);
    }

    @Test
    @DisplayName("空观测 ⇒ 单段默认色、高度 0")
    void empty() {
        assertEquals(List.of(new BeamColorSections.Section(WHITE, 0)),
            BeamColorSections.build(List.of(), WHITE));
    }

    @Test
    @DisplayName("全程空气（无玻璃）⇒ 单段白、高度 = 观测数")
    void noGlass() {
        assertEquals(List.of(new BeamColorSections.Section(WHITE, 3)),
            BeamColorSections.build(List.of(air(), air(), air()), WHITE));
    }

    @Test
    @DisplayName("第一格就是玻璃 ⇒ 白段 0 高（活信标没有信标自身格）⇒ 底部第一格即玻璃色")
    void glassAdjacent() {
        var sections = BeamColorSections.build(List.of(glass(RED), air(), air()), WHITE);
        assertEquals(2, sections.size());
        // ⚠️ 契约：白段 0 高是既定语义（2026-10-18 用户实测「改变颜色第一格不变色」的修复）⇒
        // 渲染端必须跳过 0 高段而非终止（LivingBeamRenderer.renderBeamAlong 的 continue）
        assertEquals(0, sections.get(0).height());
        assertEquals(new BeamColorSections.Section(RED, 3), sections.get(1));
    }

    @Test
    @DisplayName("玻璃隔了几格空气 ⇒ 白段 = 空气格数，玻璃段从该格起")
    void glassNotAdjacent() {
        var sections = BeamColorSections.build(List.of(air(), air(), glass(RED), air()), WHITE);
        assertEquals(2, sections.size());
        assertEquals(new BeamColorSections.Section(WHITE, 2), sections.get(0));
        assertEquals(new BeamColorSections.Section(RED, 2), sections.get(1));
    }

    @Test
    @DisplayName("两块同色玻璃 ⇒ 同一段累计高度")
    void sameColor() {
        var sections = BeamColorSections.build(List.of(glass(RED), glass(RED), air()), WHITE);
        assertEquals(2, sections.size());
        assertEquals(new BeamColorSections.Section(RED, 3), sections.get(1));
    }

    @Test
    @DisplayName("两块异色玻璃 ⇒ 第 3 段取【前段与新色的平均】（原版 FastColor.ARGB32.average）")
    void differentColorsAverage() {
        var sections = BeamColorSections.build(
            List.of(glass(RED), glass(RED), glass(BLUE), air()), WHITE);
        assertEquals(3, sections.size());
        assertEquals(new BeamColorSections.Section(WHITE, 0), sections.get(0));
        assertEquals(new BeamColorSections.Section(RED, 2), sections.get(1));
        assertEquals(new BeamColorSections.Section(FastColor.ARGB32.average(RED, BLUE), 2), sections.get(2));
    }

    @Test
    @DisplayName("不透明方块 ⇒ 停止（后面的玻璃不算）")
    void opaqueStops() {
        var sections = BeamColorSections.build(
            List.of(glass(RED), opaque(), glass(BLUE)), WHITE);
        assertEquals(2, sections.size());
        assertEquals(new BeamColorSections.Section(RED, 1), sections.get(1));
    }

    @Test
    @DisplayName("先空气再玻璃（中间无遮挡）⇒ 白段 + 玻璃段")
    void airThenGlass() {
        var sections = BeamColorSections.build(List.of(air(), glass(BLUE), air(), air()), WHITE);
        assertEquals(2, sections.size());
        assertEquals(new BeamColorSections.Section(WHITE, 1), sections.get(0));
        assertEquals(new BeamColorSections.Section(BLUE, 3), sections.get(1));
    }

    @Test
    @DisplayName("默认白色必须是完整 ARGB：RGB 白色且 alpha = 255，与原版染料色同透明度")
    void defaultWhiteIsOpaque() {
        assertEquals(0xFFFFFFFF, BeamColorSections.DEFAULT_COLOR);
        assertEquals(FastColor.ARGB32.alpha(DyeColor.WHITE.getTextureDiffuseColor()),
            FastColor.ARGB32.alpha(BeamColorSections.DEFAULT_COLOR));
    }

    @Test
    @DisplayName("实际原版光束顶点：无玻璃/红玻璃/白玻璃的内芯 alpha = 255，外晕 alpha = 32")
    void renderedBeamVertexAlpha() {
        List<List<BeamColorSections.Observation>> observations = List.of(
            List.of(), List.of(air()), List.of(glass(RED)),
            List.of(glass(DyeColor.WHITE.getTextureDiffuseColor())));
        for (var upward : observations) {
            var sections = BeamColorSections.build(upward, BeamColorSections.DEFAULT_COLOR);
            var section = sections.getLast();
            var consumer = new ColorCapture();
            BeaconRenderer.renderBeaconBeam(new PoseStack(), type -> consumer,
                BeaconRenderer.BEAM_LOCATION, 0.0F, 1.0F, 0L, 0, 1, section.color(), 0.2F, 0.25F);

            assertEquals(32, consumer.alphas.size());
            assertEquals(Collections.nCopies(16, 255), consumer.alphas.subList(0, 16),
                "内芯顶点不能因默认色丢失 alpha 而透明");
            assertEquals(Collections.nCopies(16, 32), consumer.alphas.subList(16, 32),
                "外晕透明度由原版渲染器设置");
        }
    }

    private static final class ColorCapture implements VertexConsumer {
        private final List<Integer> alphas = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            alphas.add(alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    @Test
    @DisplayName("自定义默认色（非白）同样生效")
    void customDefault() {
        var sections = BeamColorSections.build(List.of(air()), 0xFF00FF00);
        assertEquals(new BeamColorSections.Section(0xFF00FF00, 1), sections.get(0));
    }
}
