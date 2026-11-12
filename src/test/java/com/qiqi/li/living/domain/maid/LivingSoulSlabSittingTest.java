package com.qiqi.li.living.domain.maid;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LivingSoulSlabSittingTest {
    @Test void codecsRoundTripBothStates() {
        for (boolean value : new boolean[]{false, true}) {
            var data = new LivingSoulSlabSitting(value);
            var json = LivingSoulSlabSitting.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
            assertEquals(data, LivingSoulSlabSitting.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());

            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                LivingSoulSlabSitting.STREAM_CODEC.encode(buf, data);
                assertEquals(data, LivingSoulSlabSitting.STREAM_CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
            } finally {
                buf.release();
            }
        }
    }

    @Test void codecRejectsNonBoolean() {
        assertTrue(LivingSoulSlabSitting.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("1")).error().isPresent());
    }

    @Test void toggleUsesComponentPresenceForTrueAndAbsenceForFalse() {
        ItemStack stack = new ItemStack(Items.PAPER);
        assertFalse(LivingSoulSlabFunction.isSitting(stack));
        assertFalse(stack.has(MaidComponents.SITTING.get()));

        assertTrue(LivingSoulSlabFunction.toggleSitting(stack));
        assertTrue(LivingSoulSlabFunction.isSitting(stack));
        assertTrue(stack.has(MaidComponents.SITTING.get()));

        assertTrue(LivingSoulSlabFunction.toggleSitting(stack));
        assertFalse(LivingSoulSlabFunction.isSitting(stack));
        assertFalse(stack.has(MaidComponents.SITTING.get()), "false 规范化为组件缺席");
    }

    @Test void functionOwnsClockAndSittingComponents() {
        assertEquals(Set.of(MaidComponents.CLOCK.get(), MaidComponents.SITTING.get()),
                new LivingSoulSlabFunction(stack -> stack.is(Items.PAPER)).getOwnedComponentTypes());
    }
}
