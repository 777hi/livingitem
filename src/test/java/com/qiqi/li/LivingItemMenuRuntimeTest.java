package com.qiqi.li;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickableContainerContext;

import net.minecraft.world.entity.Entity;

class LivingItemMenuRuntimeTest {
    private static ContainerContext context(String key, Entity entity) {
        TickableContainerContext context = mock(TickableContainerContext.class);
        when(context.getCacheKey()).thenReturn(key);
        when(context.getEntity()).thenReturn(entity);
        return context;
    }

    @Test void mixedEntityMenuDoesNotReplacePrimarySnapshotWithEnderChest() {
        Entity maidHost = mock(Entity.class);
        assertTrue(LivingItem.shouldSendMenuRuntime(maidHost,
                context("entity_11111111-1111-1111-1111-111111111111", maidHost)));
        assertTrue(LivingItem.shouldSendMenuRuntime(maidHost,
                context("player_11111111-1111-1111-1111-111111111111", null)));
        assertFalse(LivingItem.shouldSendMenuRuntime(maidHost,
                context("player_11111111-1111-1111-1111-111111111111_ender_chest", null)));
    }

    @Test void ordinaryMenuKeepsExistingSnapshotRouting() {
        assertTrue(LivingItem.shouldSendMenuRuntime(null,
                context("player_11111111-1111-1111-1111-111111111111_ender_chest", null)));
    }
}
