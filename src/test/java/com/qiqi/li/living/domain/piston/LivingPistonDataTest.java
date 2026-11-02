package com.qiqi.li.living.domain.piston;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.qiqi.li.living.domain.piston.PistonComponents;
import com.qiqi.li.living.model.Pos2D;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LivingPistonDataTest {

    @ParameterizedTest
    @CsvSource({"0,-1", "0,1", "-1,0", "1,0"})
    void directionCodecsRoundTripByValue(int x, int y) {
        var data = new LivingPistonData(new Pos2D(x, y));
        var json = LivingPistonData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        assertEquals(Set.of("direction"), json.getAsJsonObject().keySet());
        assertEquals(data, LivingPistonData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());

        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            LivingPistonData.STREAM_CODEC.encode(buf, data);
            assertEquals(data, LivingPistonData.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "2,0", "0,-2"})
    void nonCardinalDirectionsAreRejected(int x, int y) {
        Pos2D direction = new Pos2D(x, y);
        assertThrows(IllegalArgumentException.class, () -> new LivingPistonData(direction));
        var json = JsonParser.parseString("{\"direction\":{\"x\":" + x + ",\"y\":" + y + "}}");
        assertTrue(LivingPistonData.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());

        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            Pos2D.STREAM_CODEC.encode(buf, direction);
            assertThrows(IllegalArgumentException.class, () -> LivingPistonData.STREAM_CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void missingDirectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LivingPistonData(null));
        assertTrue(LivingPistonData.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{}")).error().isPresent());
    }

    @Test
    void componentDefaultsUpAndDefaultWriteRemovesIt() {
        var stack = new ItemStack(Items.PISTON);
        assertEquals(Pos2D.UP, LivingPistonData.of(stack).direction());
        assertFalse(stack.has(PistonComponents.LIVING_PISTON_DATA.value()));

        LivingPistonData.set(stack, new LivingPistonData(Pos2D.RIGHT));
        assertEquals(Pos2D.RIGHT, LivingPistonData.of(stack).direction());
        assertTrue(stack.has(PistonComponents.LIVING_PISTON_DATA.value()));

        LivingPistonData.set(stack, new LivingPistonData(new Pos2D(0, -1)));
        assertFalse(stack.has(PistonComponents.LIVING_PISTON_DATA.value()));
        assertEquals(LivingPistonData.DEFAULT, LivingPistonData.of(stack));
    }

    @Test
    void updatingDirectionDoesNotMutateOriginal() {
        var original = new LivingPistonData(Pos2D.RIGHT);
        assertEquals(Pos2D.LEFT, original.withDirection(Pos2D.LEFT).direction());
        assertEquals(Pos2D.RIGHT, original.direction());
    }

    @Test
    void functionDeclaresExactlyItsDirectionComponent() {
        var function = new LivingPistonFunction();
        assertEquals(Set.of(PistonComponents.LIVING_PISTON_DATA.value()), function.getOwnedComponentTypes());
        assertTrue(function.getIgnoredComponentTypes().isEmpty());
    }
}
