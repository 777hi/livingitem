package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * {@link LivingToolMemory} 的接缝测试。
 *
 * <p>它是活工具/活武器唯一的持久化记忆：录制偏移必须<b>逐位保留</b>（不归一化），
 * 回放时以宿主为起点加回原偏移；三条记忆互相独立、只保留最新一条。
 * 这里还钉住 Codec / StreamCodec 的往返（落盘与联机同步两条通道）。</p>
 */
class LivingToolMemoryTest {

    private static LivingToolMemory.RayMemory ray(double x, double y, double z) {
        return new LivingToolMemory.RayMemory(new Vec3(x, y, z), null);
    }

    @Test
    @DisplayName("射线记忆:offset 精确保留,回放把宿主起点加回原命中点")
    void rayMemory_recordsExactOffsetAndReplaysFromAnyOrigin() {
        Vec3 eye = new Vec3(1, 2, 3);
        Vec3 hit = new Vec3(4, 6, 8);

        LivingToolMemory.RayMemory memory = LivingToolMemory.RayMemory.record(eye, hit, Blocks.STONE);

        assertEquals(new Vec3(3, 4, 5), memory.offset(), "offset 必须是完整偏移，不做归一化");
        assertEquals(hit, memory.endpointFrom(eye), "从原眼睛位置回放必须回到原命中点");
        assertEquals(new Vec3(13, 24, 35), memory.endpointFrom(new Vec3(10, 20, 30)),
            "换宿主后终点 = 新起点 + 同一偏移");
        assertTrue(memory.restrictsBlock());
        assertTrue(memory.matches(Blocks.STONE));
        assertFalse(memory.matches(Blocks.DIRT));

        LivingToolMemory.RayMemory any = LivingToolMemory.RayMemory.record(eye, hit, null);
        assertFalse(any.restrictsBlock());
        assertTrue(any.matches(Blocks.DIRT), "null = 不限类型");
    }

    @Test
    @DisplayName("攻击记忆:与射线同构,按 EntityType 匹配")
    void attackMemory_recordsAndMatchesEntityType() {
        Vec3 eye = new Vec3(0, 1, 0);
        Vec3 target = new Vec3(0, 2, 3);

        LivingToolMemory.AttackMemory memory = LivingToolMemory.AttackMemory.record(
            eye, target, EntityType.ZOMBIE);

        assertEquals(new Vec3(0, 1, 3), memory.offset());
        assertEquals(target, memory.endpointFrom(eye));
        assertTrue(memory.restrictsEntity());
        assertTrue(memory.matches(EntityType.ZOMBIE));
        assertFalse(memory.matches(EntityType.COW));

        LivingToolMemory.AttackMemory any = LivingToolMemory.AttackMemory.record(eye, target, null);
        assertFalse(any.restrictsEntity());
        assertTrue(any.matches(EntityType.COW));
    }

    @Test
    @DisplayName("不可变更新:with/without 只动目标字段,其余保持同一对象")
    void withAndWithout_onlyReplaceTargetField() {
        LivingToolMemory.RayMemory dig = ray(1, 0, 0);
        LivingToolMemory.RayMemory use = ray(0, 1, 0);
        LivingToolMemory.AttackMemory attack = new LivingToolMemory.AttackMemory(
            new Vec3(0, 0, 1), EntityType.ZOMBIE);
        LivingToolMemory memory = new LivingToolMemory(dig, use, attack);

        assertTrue(memory.hasDig());
        assertTrue(memory.hasUse());
        assertTrue(memory.hasAttack());
        assertFalse(memory.isEmpty());

        LivingToolMemory.RayMemory newDig = ray(2, 0, 0);
        LivingToolMemory replaced = memory.withDig(newDig);
        assertSame(newDig, replaced.dig());
        assertSame(use, replaced.use());
        assertSame(attack, replaced.attack());

        LivingToolMemory withoutDig = memory.withoutDig();
        assertNull(withoutDig.dig());
        assertSame(use, withoutDig.use());
        assertSame(attack, withoutDig.attack());

        assertNull(memory.withoutUse().use());
        assertSame(dig, memory.withoutUse().dig());
        assertNull(memory.withoutAttack().attack());
        assertSame(dig, memory.withoutAttack().dig());
        assertTrue(LivingToolMemory.DEFAULT.isEmpty());
    }

    @Test
    @DisplayName("持久化 Codec:全字段与空记忆往返一致")
    void codec_roundTrip() {
        LivingToolMemory memory = new LivingToolMemory(
            new LivingToolMemory.RayMemory(new Vec3(1.5, 2.5, 3.5), Blocks.STONE),
            new LivingToolMemory.RayMemory(new Vec3(-1, 0, 4), null),
            new LivingToolMemory.AttackMemory(new Vec3(0, 1, 2), EntityType.ZOMBIE));

        var json = LivingToolMemory.CODEC.encodeStart(JsonOps.INSTANCE, memory).getOrThrow();
        assertEquals(memory, LivingToolMemory.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());

        var emptyJson = LivingToolMemory.CODEC.encodeStart(JsonOps.INSTANCE, LivingToolMemory.DEFAULT)
            .getOrThrow();
        assertEquals(LivingToolMemory.DEFAULT,
            LivingToolMemory.CODEC.parse(JsonOps.INSTANCE, emptyJson).getOrThrow());
    }

    @Test
    @DisplayName("网络 StreamCodec:全字段与空记忆往返一致")
    void streamCodec_roundTrip() {
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        LivingToolMemory memory = new LivingToolMemory(
            new LivingToolMemory.RayMemory(new Vec3(1.5, 2.5, 3.5), Blocks.STONE),
            new LivingToolMemory.RayMemory(new Vec3(-1, 0, 4), null),
            new LivingToolMemory.AttackMemory(new Vec3(0, 1, 2), EntityType.ZOMBIE));

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        LivingToolMemory.STREAM_CODEC.encode(buf, memory);
        assertEquals(memory, LivingToolMemory.STREAM_CODEC.decode(buf));

        RegistryFriendlyByteBuf emptyBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        LivingToolMemory.STREAM_CODEC.encode(emptyBuf, LivingToolMemory.DEFAULT);
        assertEquals(LivingToolMemory.DEFAULT, LivingToolMemory.STREAM_CODEC.decode(emptyBuf));
    }
}
