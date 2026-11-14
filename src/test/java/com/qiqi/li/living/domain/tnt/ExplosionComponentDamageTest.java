package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 即时实体伤害只遍历当前已加载实体，并在 float 边界保持有限。 */
class ExplosionComponentDamageTest {

    private static ServerLevel level(Iterable<Entity> entities) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(entities);
        DamageSources sources = mock(DamageSources.class);
        when(level.damageSources()).thenReturn(sources);
        when(sources.explosion(null, null)).thenReturn(mock(DamageSource.class));
        return level;
    }

    private static LivingEntity living(double x, double y, double z) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getX()).thenReturn(x);
        when(entity.getY()).thenReturn(y);
        when(entity.getZ()).thenReturn(z);
        when(entity.getDeltaMovement()).thenReturn(Vec3.ZERO);
        return entity;
    }

    @Test
    void hugeRadiusUsesLoadedIterableAndSaturatesDamage() {
        LivingEntity center = living(0, 0, 0);
        ServerLevel level = level(List.of(center));

        ExplosionComponent.applyExplosionDamage(level, 0, 0, 0, Double.MAX_VALUE);

        verify(center).hurt(any(DamageSource.class), floatThat(Float::isFinite));
        verify(center).hurt(any(DamageSource.class), eq(Float.MAX_VALUE));
        verify(level, never()).getEntities(isNull(), any(AABB.class));
    }

    @Test
    void rangeAndItemExclusionsRemainUnchanged() {
        LivingEntity inside = living(3, 0, 0);
        LivingEntity outside = living(11, 0, 0);
        ItemEntity item = mock(ItemEntity.class);
        when(item.getX()).thenReturn(0.0);
        when(item.getY()).thenReturn(0.0);
        when(item.getZ()).thenReturn(0.0);
        ServerLevel level = level(List.of(inside, outside, item));

        ExplosionComponent.applyExplosionDamage(level, 0, 0, 0, 10);

        verify(inside).hurt(any(DamageSource.class), anyFloat());
        verify(outside, never()).hurt(any(DamageSource.class), anyFloat());
        verify(item, never()).setDeltaMovement(any(Vec3.class));
        verify(inside).setDeltaMovement(any(Vec3.class));
    }

    @Test
    void zeroDistanceDamageDoesNotCreateInvalidKnockback() {
        LivingEntity center = living(0, 0, 0);
        ServerLevel level = level(List.of(center));

        ExplosionComponent.applyExplosionDamage(level, 0, 0, 0, 4);

        verify(center).hurt(any(DamageSource.class), anyFloat());
        verify(center, never()).setDeltaMovement(any(Vec3.class));
    }

    @Tag("perf")
    @Test
    void measureLoadedEntityTraversal() {
        List<Entity> entities = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) entities.add(living(i % 100, 64, i / 100));
        ServerLevel level = level(entities);
        long start = System.nanoTime();
        ExplosionComponent.applyExplosionDamage(level, 0, 64, 0, 10_000);
        System.out.printf("TNT damage traversal 10k loaded entities: %.3f ms%n",
            (System.nanoTime() - start) / 1_000_000.0);
    }
}
