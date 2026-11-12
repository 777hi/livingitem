package com.qiqi.li.living.domain.maid;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 活魂符自有排期；女仆快照仍由原模组管理。 */
public final class MaidComponents {
    public static final DeferredRegister<DataComponentType<?>> REG =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingSoulSlabClock>> CLOCK =
            REG.register("living_soul_slab_clock", () -> DataComponentType.<LivingSoulSlabClock>builder()
                    .persistent(LivingSoulSlabClock.CODEC)
                    .networkSynchronized(LivingSoulSlabClock.STREAM_CODEC)
                    .build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingSoulSlabSitting>> SITTING =
            REG.register("living_soul_slab_sitting", () -> DataComponentType.<LivingSoulSlabSitting>builder()
                    .persistent(LivingSoulSlabSitting.CODEC)
                    .networkSynchronized(LivingSoulSlabSitting.STREAM_CODEC)
                    .build());

    private MaidComponents() {}
}
