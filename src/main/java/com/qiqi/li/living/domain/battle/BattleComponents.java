package com.qiqi.li.living.domain.battle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qiqi.li.living.api.LivingMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

import net.minecraft.core.component.DataComponentType;

/**
 * 战局领域组件：战损（当前层已损）随物品走（D-battle-04）。
 *
 * <p>⚠️ 本组件<b>不挂在任何 function 的 owned 集</b>（战局功能 canApply=false 不认领物品）
 * ⇒ 取消活化不清它，改由<b>战局边界清零</b>：建局 / 清场 / 战败时全容器扫槽剥离
 * （{@code BattleDamageSweep}）。残留的哑组件无任何行为，alpha 可容忍。</p>
 *
 * <p>⚠️ {@code REG.register(modEventBus)} 挂 {@code LivingItem} 构造函数（R4 同实体注册口径）。</p>
 */
public final class BattleComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<DataComponentType<?>, DataComponentType<BattleDamage>> BATTLE_DAMAGE =
        REG.register("battle_damage", () -> DataComponentType.<BattleDamage>builder()
            .persistent(BattleDamage.CODEC)
            .networkSynchronized(BattleDamage.STREAM_CODEC)
            .build());

    private BattleComponents() {}

    /** 当前层已损（0 ~ 单件血量-1）；count 即剩余完整层数（§4.2 血量统一模型）。 */
    public record BattleDamage(int currentLayerDamage) {
        public static final BattleDamage ZERO = new BattleDamage(0);

        public static final Codec<BattleDamage> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.INT.fieldOf("damage").forGetter(BattleDamage::currentLayerDamage))
                .apply(instance, BattleDamage::new));

        public static final StreamCodec<FriendlyByteBuf, BattleDamage> STREAM_CODEC =
            StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.VAR_INT, BattleDamage::currentLayerDamage,
                BattleDamage::new);

        /** 读栈上的战损；无组件按 0（不写入）。 */
        public static int of(ItemStack stack) {
            BattleDamage damage = stack.get(BATTLE_DAMAGE.get());
            return damage == null ? 0 : Math.max(0, damage.currentLayerDamage());
        }
    }
}
