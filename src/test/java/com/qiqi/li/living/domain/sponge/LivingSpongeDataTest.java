package com.qiqi.li.living.domain.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * {@link LivingSpongeData} 的干/湿不变量与落盘 CODEC（设计稿 docs/buffer/活海绵.md §2.1）。
 *
 * <p>记账算术（均分 / 拆分守恒）的守卫随第二段记账内核一起落（§9.4 规则 8/13），
 * 这里只钉组件自身的形状契约。</p>
 */
class LivingSpongeDataTest {

    @Test
    @DisplayName("① 干/湿不变量：无流体或非正存量归一为干态")
    void invariant_normalizesToDry() {
        assertNull(new LivingSpongeData(null, 0).fluid());
        assertNull(new LivingSpongeData(null, 5_000).fluid(), "无流体的存量 = 幽灵湿海绵，归干");
        assertEquals(0, new LivingSpongeData(Fluids.WATER.getFluidType(), 0).mmB(), "零存量 = 干态");
        assertEquals(0, new LivingSpongeData(Fluids.WATER.getFluidType(), -1).mmB(), "负存量 = 干态");

        LivingSpongeData wet = new LivingSpongeData(Fluids.WATER.getFluidType(), 1);
        assertTrue(wet.isWet());
        assertTrue(new LivingSpongeData(Fluids.WATER.getFluidType(), 0).isWet() == false);
        assertFalse(LivingSpongeData.DEFAULT.isWet());
    }

    @Test
    @DisplayName("② 容量与总量：每枚 6.4e7 mmB，堆总量 = mmB × count（long 域）")
    void capacityAndTotals() {
        assertEquals(64_000_000L, LivingSpongeData.MAX_MM_B, "每枚 64,000 mB = 64 源当量");
        assertEquals(64_000_000L * 64, LivingSpongeData.totalCapacityMmB(64), "堆容量线性 ×count");
        assertEquals(857L * 7, new LivingSpongeData(Fluids.WATER.getFluidType(), 857).totalMmB(7));
    }

    @Test
    @DisplayName("③ CODEC 往返：湿海绵 {fluid, mmB} 不失真")
    void codec_roundTripWet() {
        LivingSpongeData wet = new LivingSpongeData(Fluids.WATER.getFluidType(), 857L);

        var ops = NbtOps.INSTANCE;
        var tag = LivingSpongeData.CODEC.encodeStart(ops, wet).getOrThrow();
        LivingSpongeData restored = LivingSpongeData.CODEC.parse(ops, tag).getOrThrow();

        assertSame(Fluids.WATER.getFluidType(), restored.fluid());
        assertEquals(857L, restored.mmB());
    }

    @Test
    @DisplayName("④ CODEC 往返：干海绵 fluid 字段为空串，读回仍是干态")
    void codec_roundTripDry() {
        var ops = NbtOps.INSTANCE;
        var tag = LivingSpongeData.CODEC.encodeStart(ops, LivingSpongeData.DEFAULT).getOrThrow();
        LivingSpongeData restored = LivingSpongeData.CODEC.parse(ops, tag).getOrThrow();

        assertNull(restored.fluid());
        assertEquals(0, restored.mmB());
        assertEquals(LivingSpongeData.DEFAULT, restored, "干态往返 = 默认值（组件可被默认值移除的前提）");
    }

    @Test
    @DisplayName("⑤ 岩浆湿海绵同样往返（吸收任意已注册流体，规则 1）")
    void codec_roundTripLava() {
        FluidType lava = Fluids.LAVA.getFluidType();
        LivingSpongeData data = new LivingSpongeData(lava, LivingSpongeData.MAX_MM_B);

        var ops = NbtOps.INSTANCE;
        var tag = LivingSpongeData.CODEC.encodeStart(ops, data).getOrThrow();
        LivingSpongeData restored = LivingSpongeData.CODEC.parse(ops, tag).getOrThrow();

        assertSame(lava, restored.fluid());
        assertEquals(LivingSpongeData.MAX_MM_B, restored.mmB());
    }

    @Test
    @DisplayName("⑥ 未知流体 id + 正存量 ⇒ 归干态（I-C6 同款宁损勿鬼，不许幽灵湿海绵）")
    void codec_unknownFluidClampsToDry() {
        var ops = NbtOps.INSTANCE;
        // fluid 字段给注册表里不存在的 id，模拟「提供该流体的模组被移除后读旧档」
        var broken = ops.createMap(java.util.Map.of(
            ops.createString("fluid"), ops.createString("somemod:vanished_fluid"),
            ops.createString("mm_b"), ops.createLong(857L)));

        LivingSpongeData restored = LivingSpongeData.CODEC.parse(ops, broken).getOrThrow();
        assertNull(restored.fluid());
        assertEquals(0, restored.mmB());
    }

    @Test
    @DisplayName("⑦ 不可变派生：withXxx 产生新实例，原值不动")
    void withers_areImmutable() {
        FluidType water = Fluids.WATER.getFluidType();
        LivingSpongeData base = new LivingSpongeData(water, 1_000);

        LivingSpongeData grown = base.withMmB(2_000);
        assertEquals(1_000, base.mmB());
        assertEquals(2_000, grown.mmB());

        LivingSpongeData dried = grown.withMmB(0);
        assertNull(dried.fluid(), "存量归零 ⇒ 干态（fluid 一并归一）");
        assertEquals(2_000, grown.mmB());
    }
}
