package com.qiqi.li.living.domain.brewing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.brewing.BrewingDirectionData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

/**
 * 活酿造台数据组件的 codec 守卫（2026-10-12 反馈层新增 {@code brewStartTick}）。
 *
 * <p>守两件事：① <b>旧数据可解码</b>——第一段只落过 {@code direction}、第二段落了 {@code fuel}，
 * 两个持久字段都是 {@code optionalFieldOf}，缺字段不得炸；② <b>新字段真的在 codec 里</b>——
 * 忘记把字段加进 {@code RecordCodecBuilder} / {@code StreamCodec.composite} 不报编译错，
 * 只会在往返后静默变默认值（白幕因此永远不出现的典型死法）。</p>
 */
class LivingBrewingStandDataTest {

    /** 构造一份「旧数据」tag：只有 direction（第一段形态），无 fuel / brew_start_tick。 */
    private static CompoundTag legacyTag() {
        var ops = NbtOps.INSTANCE;
        CompoundTag tag = (CompoundTag) LivingBrewingStandData.CODEC
            .encodeStart(ops, LivingBrewingStandData.DEFAULT).getOrThrow();
        tag.remove("fuel");
        tag.remove("brew_start_tick");
        assertEquals(1, tag.size(), "旧数据只应剩 direction 一个字段");
        return tag;
    }

    @Test
    @DisplayName("旧数据（只有 direction）可解码：fuel=0、brewStartTick=-1（不在酿造）")
    void legacyData_decodes() {
        var decoded = LivingBrewingStandData.CODEC
            .parse(NbtOps.INSTANCE, legacyTag()).getOrThrow();

        assertEquals(0, decoded.fuel(), "缺 fuel ⇒ 默认 0");
        assertEquals(-1L, decoded.brewStartTick(), "缺 brew_start_tick ⇒ -1（白幕收起）");
    }

    @Test
    @DisplayName("codec 往返：fuel 与 brew_start_tick 都保留（忘了加字段会在这里现形）")
    void roundTrip_preservesAllFields() {
        var original = LivingBrewingStandData.DEFAULT.withFuel(7).withBrewStart(123456L);

        var ops = NbtOps.INSTANCE;
        var encoded = LivingBrewingStandData.CODEC.encodeStart(ops, original).getOrThrow();
        var decoded = LivingBrewingStandData.CODEC.parse(ops, encoded).getOrThrow();

        assertEquals(original, decoded, "往返应等值");
        assertEquals(7, decoded.fuel());
        assertEquals(123456L, decoded.brewStartTick());
    }

    @Test
    @DisplayName("withBrewStart 只改起始时刻（燃料 / 方向不被顺手抹掉）")
    void withBrewStart_isolatedField() {
        var base = LivingBrewingStandData.DEFAULT.withFuel(3);

        var updated = base.withBrewStart(999L);

        assertEquals(3, updated.fuel(), "燃料应保留");
        assertEquals(BrewingDirectionData.DEFAULT_BREWING, updated.direction(), "方向应保留");
        assertEquals(999L, updated.brewStartTick());
        assertEquals(-1L, base.brewStartTick(), "原对象不被修改（record 不可变）");
    }

    @Test
    @DisplayName("默认值即「移除组件」锚点：等于 DEFAULT 时 set 不落组件")
    void defaultIsRemovalAnchor() {
        // 间接验证语义：DEFAULT 的 brewStartTick 必须是「不在酿造」，否则每台中控台都会
        // 永久挂着一个组件（且 set() 的等值移除永远不触发）
        assertTrue(LivingBrewingStandData.DEFAULT.brewStartTick() < 0);
        assertFalse(LivingBrewingStandData.DEFAULT.withBrewStart(0).equals(LivingBrewingStandData.DEFAULT),
            "起始时刻 >=0 必须与 DEFAULT 不等，否则白幕状态永远写不进物品");
    }
}
