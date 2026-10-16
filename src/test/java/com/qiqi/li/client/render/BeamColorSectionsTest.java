package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.util.FastColor;

/**
 * BeamColorSections 纯逻辑测试（照原版 {@code BeaconBlockEntity} 扫描算法）。
 *
 * <p>判据来源：原版源码（2026-10-16 核）—— 首块染色方块纯色、第 2 块仍纯色、
 * 第 3 块起异色取平均；非染色方块加高；不透明停止。我们以"白段高度 1"起步
 * （= 原版信标自身那一格）。</p>
 */
class BeamColorSectionsTest {

    private static final int WHITE = 0xFFFFFF;
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
    @DisplayName("第一格就是玻璃 ⇒ 白段高度 1（信标自身格）+ 玻璃色段覆盖其余")
    void glassAdjacent() {
        var sections = BeamColorSections.build(List.of(glass(RED), air(), air()), WHITE);
        assertEquals(2, sections.size());
        // ⚠️ 契约：首段高度必须 ≥ 1 —— 渲染循环曾把 0 高段当终止条件 ⇒ 贴着放玻璃时一条不画
        // （2026-10-16 用户实测「贴箱口放玻璃光束消失、隔一格正常」的根因）
        assertTrue(sections.get(0).height() >= 1);
        assertEquals(new BeamColorSections.Section(WHITE, 1), sections.get(0));
        assertEquals(new BeamColorSections.Section(RED, 3), sections.get(1));
    }

    @Test
    @DisplayName("玻璃隔了几格空气 ⇒ 白段先长高，玻璃段从该格起")
    void glassNotAdjacent() {
        var sections = BeamColorSections.build(List.of(air(), air(), glass(RED), air()), WHITE);
        assertEquals(2, sections.size());
        assertEquals(new BeamColorSections.Section(WHITE, 3), sections.get(0));
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
        assertEquals(new BeamColorSections.Section(WHITE, 1), sections.get(0));
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
        assertEquals(new BeamColorSections.Section(WHITE, 2), sections.get(0));
        assertEquals(new BeamColorSections.Section(BLUE, 3), sections.get(1));
    }

    @Test
    @DisplayName("自定义默认色（非白）同样生效")
    void customDefault() {
        var sections = BeamColorSections.build(List.of(air()), 0xFF00FF00);
        assertEquals(new BeamColorSections.Section(0xFF00FF00, 1), sections.get(0));
    }
}
