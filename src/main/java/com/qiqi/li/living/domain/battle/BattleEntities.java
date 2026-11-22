package com.qiqi.li.living.domain.battle;

import java.util.function.Supplier;

import com.qiqi.li.living.api.LivingMod;
import com.qiqi.li.living.domain.battle.entity.BattleZombieEntity;
import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 战场实体注册（项目首例实体）。
 *
 * <p>⚠️ {@code REG.register(modEventBus)} 挂在 {@code LivingItem} 构造函数（同各域组件 REG），
 * 不能落在本包的 {@code *Registration}（check_layers R4 反向检查）。属性注册走
 * {@link #registerAttributes}（LivingEntity 必须）。</p>
 *
 * <p>⚠️ <b>每新增一个 EntityType 必须同时在客户端注册 renderer</b>
 * （{@code LivingItemClient#onRegisterRenderers}）：缺 renderer 的实体一旦同步到客户端，
 * 世界渲染循环 {@code EntityRenderDispatcher.shouldRender} 直接 NPE 崩溃
 * （2026-11-22 实测：battle_zombie 漏注册 ⇒ wave 命令即崩）。</p>
 */
public final class BattleEntities {

    public static final DeferredRegister<EntityType<?>> REG =
        DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, LivingMod.ID);

    public static final Supplier<EntityType<BridgeEntity>> BRIDGE = REG.register("battle_bridge",
        () -> EntityType.Builder.of(BridgeEntity::new, MobCategory.MISC)
            .sized(1.0F, 1.0F).clientTrackingRange(10).updateInterval(2)
            .build("battle_bridge"));

    public static final Supplier<EntityType<BattleZombieEntity>> ZOMBIE = REG.register("battle_zombie",
        () -> EntityType.Builder.of(BattleZombieEntity::new, MobCategory.MISC)
            .sized(0.6F, 1.95F).clientTrackingRange(10).updateInterval(2)
            .build("battle_zombie"));

    private BattleEntities() {}

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(BRIDGE.get(), Mob.createMobAttributes().build());
        event.put(ZOMBIE.get(), Zombie.createAttributes().build());
    }
}
