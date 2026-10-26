package com.qiqi.li.living.domain.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

/**
 * 组件装饰条目与命中装饰的<b>同一处匹配</b>（2026-10-27 审查 P2-2）。
 *
 * <p><b>被守的行为</b>：{@code getTargetPointWorldPos} 的组件精确匹配曾把
 * {@code Entry.x()}（double 世界坐标）与 {@code MapDecoration.x()}（byte 像素）
 * 直接相比 ⇒ 恒 false（死代码），兜底退化成「组件里第一个可传送条目」⇒
 * 多条目地图点哪个都被第一个劫持（探险图 + 玩家标记共存时必中）。
 * 修复：类型一致 + 世界坐标换算像素一致（±0.5 覆盖半像素取整）才用精确值，
 * 否则回退像素反推。</p>
 */
class MapCoordHelperMatchTest {

    private static final double CENTER = 0.0;
    private static final int SCALE = 2;

    private static MapDecorations.Entry entry(String key, net.minecraft.core.Holder<MapDecorationType> type, double x, double z) {
        return new MapDecorations.Entry(type, x, z, 0.0F);
    }

    private static MapDecorations decorations(MapDecorations.Entry... entries) {
        Map<String, MapDecorations.Entry> map = new LinkedHashMap<>();
        int i = 0;
        for (MapDecorations.Entry e : entries) {
            map.put("deco" + (i++), e);
        }
        return new MapDecorations(map);
    }

    /** 世界坐标 → 该地图的装饰 byte 像素（与像素反推公式互逆）。 */
    private static byte worldToDecoPixel(double world, double center) {
        return (byte) (((world - center) / SCALE + 64.0 - 64.0) * 2);
    }

    @Test
    @DisplayName("条目与命中装饰同一处 ⇒ 用组件精确坐标（非像素反推的整数值）")
    void matchedEntry_returnsPreciseWorldPos() {
        // 条目世界 (10.37, 20.62) → 像素 69.185/74.31 → 命中装饰 byte 只能是 (10, 20)
        MapDecorations deco = decorations(
            entry("a", MapDecorationTypes.TARGET_X, 10.37, 20.62));
        MapDecoration hit = new MapDecoration(
            MapDecorationTypes.TARGET_X, (byte) 10, (byte) 20, (byte) 0, Optional.empty());

        double[] out = MapCoordHelper.matchTeleportableEntry(deco, hit, CENTER, CENTER, SCALE);

        assertArrayEquals(new double[]{10.37, 20.62}, out, 0.0001,
            "精确坐标 ≠ 像素反推的 (10.0, 20.0) —— 用精确值才算修好");
    }

    @Test
    @DisplayName("多条目地图：命中第二处的条目 ⇒ 返回第二处（旧兜底返回第一个=劫持）")
    void multipleEntries_returnsTheMatchedOne() {
        MapDecorations deco = decorations(
            entry("far", MapDecorationTypes.TARGET_X, 100.0, 100.0),
            entry("near", MapDecorationTypes.TARGET_X, 10.37, 20.62));
        MapDecoration hit = new MapDecoration(
            MapDecorationTypes.TARGET_X, (byte) 10, (byte) 20, (byte) 0, Optional.empty());

        double[] out = MapCoordHelper.matchTeleportableEntry(deco, hit, CENTER, CENTER, SCALE);

        assertArrayEquals(new double[]{10.37, 20.62}, out, 0.0001,
            "点哪处传哪处 —— 旧兜底恒传第一条（劫持）");
    }

    @Test
    @DisplayName("类型不同 / 像素对不上 ⇒ null（调用方回退像素反推）")
    void noMatch_returnsNull() {
        MapDecorations deco = decorations(
            entry("a", MapDecorationTypes.TARGET_X, 10.37, 20.62));
        MapDecoration otherType = new MapDecoration(
            MapDecorationTypes.PLAYER, (byte) 10, (byte) 20, (byte) 0, Optional.empty());
        assertNull(MapCoordHelper.matchTeleportableEntry(deco, otherType, CENTER, CENTER, SCALE),
            "类型不同不许借用精确坐标");

        MapDecoration otherPlace = new MapDecoration(
            MapDecorationTypes.TARGET_X, (byte) 40, (byte) 40, (byte) 0, Optional.empty());
        assertNull(MapCoordHelper.matchTeleportableEntry(deco, otherPlace, CENTER, CENTER, SCALE),
            "同一类型但位置对不上 ⇒ 回退像素反推（点装饰所在地）");
    }

    @Test
    @DisplayName("像素反推公式与正变换互逆（换算口径回归）")
    void inverseTransform_roundTrips() {
        double world = 123.45;
        float pixel = (float) ((world - CENTER) / SCALE + 64.0);
        byte decoPixel = (byte) ((pixel - 64.0) * 2);
        float pixelAgain = decoPixel / 2.0F + 64.0F;
        double worldAgain = CENTER + (pixelAgain - 64.0F) * SCALE;
        assertEquals(world, worldAgain, SCALE / 2.0,
            "半像素精度内互逆（容差 = 1 像素对应的世界距离一半）");
    }
}
