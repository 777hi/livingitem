package com.qiqi.li.living.domain.map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

/**
 * 结构标记与物品自带 {@code MAP_DECORATIONS} 条目的"同处"判定。
 *
 * <p><b>被守的行为</b>：原版藏宝图的目标条目写的是宝箱 {@code (min+9, min+9)}
 * （{@code buried_treasures} 的 {@code locate_offset [9,0,9]}），而结构标记曾按区块中心
 * {@code (min+8, min+8)} 打点 ⇒ 同一张图出现两枚红叉。命中条目后以条目为准，
 * 旧扫描写下的同 id 错位标记也要一并清掉。</p>
 */
class StructureMapDecoratorComponentEntryTest {

    /** zoom 1（scale=1）时地图中心恒为 128 的倍数。 */
    private static final int CENTER = 4864;
    private static final int BLOCKS_PER_PIXEL = 2;

    private static MapDecorations decorations(MapDecorations.Entry... entries) {
        Map<String, MapDecorations.Entry> map = new LinkedHashMap<>();
        int i = 0;
        for (MapDecorations.Entry e : entries) {
            map.put("+" + (i++), e);
        }
        return new MapDecorations(map);
    }

    @Test
    @DisplayName("条目坐标就是落点 ⇒ 命中（不再额外补一枚）")
    void samePosition_matches() {
        MapDecorations deco = decorations(
            new MapDecorations.Entry(MapDecorationTypes.RED_X, 4809.0, 4809.0, 180.0F));

        assertNotNull(StructureMapDecorator.findComponentCoveringEntry(
            deco, CENTER, CENTER, BLOCKS_PER_PIXEL, 4809, 4809, MapDecorationTypes.RED_X));
    }

    @Test
    @DisplayName("差半像素（邻格）仍算同处 ⇒ 命中（±0.5 容差）")
    void halfPixel_matches() {
        MapDecorations deco = decorations(
            new MapDecorations.Entry(MapDecorationTypes.RED_X, 4809.0, 4809.0, 180.0F));

        assertNotNull(StructureMapDecorator.findComponentCoveringEntry(
            deco, CENTER, CENTER, BLOCKS_PER_PIXEL, 4808, 4808, MapDecorationTypes.RED_X),
            "4808 与 4809 在 zoom 1 地图上只差半像素 —— 属于同一处，不许再补一枚");
    }

    @Test
    @DisplayName("差 8 格（区块角 vs 区块中心的旧口径）⇒ 不命中，按落点自己打点")
    void farApart_noMatch() {
        MapDecorations deco = decorations(
            new MapDecorations.Entry(MapDecorationTypes.WOODLAND_MANSION, 4809.0, 4809.0, 180.0F));

        assertNull(StructureMapDecorator.findComponentCoveringEntry(
            deco, CENTER, CENTER, BLOCKS_PER_PIXEL, 4817, 4817, MapDecorationTypes.WOODLAND_MANSION));
    }

    @Test
    @DisplayName("图标类型不同 ⇒ 不命中（不许借用玩家标记 / 其它图标条目）")
    void differentIcon_noMatch() {
        MapDecorations deco = decorations(
            new MapDecorations.Entry(MapDecorationTypes.RED_MARKER, 4809.0, 4809.0, 0.0F));

        assertNull(StructureMapDecorator.findComponentCoveringEntry(
            deco, CENTER, CENTER, BLOCKS_PER_PIXEL, 4809, 4809, MapDecorationTypes.RED_X));
    }

    @Test
    @DisplayName("物品没有装饰条目（模组自建地图）⇒ 不命中")
    void emptyComponents_noMatch() {
        assertNull(StructureMapDecorator.findComponentCoveringEntry(
            MapDecorations.EMPTY, CENTER, CENTER, BLOCKS_PER_PIXEL, 4809, 4809, MapDecorationTypes.RED_X));
    }
}
