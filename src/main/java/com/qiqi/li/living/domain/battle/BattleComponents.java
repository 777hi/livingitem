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

    /** 战局进度（当前波次）——随瓶迁移（D-battle-06），取消活化清空（瓶 function owned 集）。 */
    public static final net.neoforged.neoforge.registries.DeferredHolder<DataComponentType<?>, DataComponentType<BattleProgress>> BATTLE_PROGRESS =
        REG.register("battle_progress", () -> DataComponentType.<BattleProgress>builder()
            .persistent(BattleProgress.CODEC)
            .networkSynchronized(BattleProgress.STREAM_CODEC)
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

    /** 战局进度：当前波次（1 起；0 = 未开战）。⏳ 波次机（F）落地后由它推进。 */
    public record BattleProgress(int wave) {
        public static final BattleProgress DEFAULT = new BattleProgress(1);

        public static final Codec<BattleProgress> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.INT.fieldOf("wave").forGetter(BattleProgress::wave))
                .apply(instance, BattleProgress::new));

        public static final StreamCodec<FriendlyByteBuf, BattleProgress> STREAM_CODEC =
            StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.VAR_INT, BattleProgress::wave,
                BattleProgress::new);

        /** 读瓶上的进度；无组件按默认（第 1 波）。 */
        public static BattleProgress of(ItemStack stack) {
            BattleProgress progress = stack.get(BATTLE_PROGRESS.get());
            return progress == null ? DEFAULT
                : new BattleProgress(Math.max(1, progress.wave()));
        }
    }
}
