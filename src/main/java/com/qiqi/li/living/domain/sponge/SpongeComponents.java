package com.qiqi.li.living.domain.sponge;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活海绵领域的组件注册总线（{@code PowerComponents} 同款结构：总线单独一类，
 * 具体组件定义在各 {@code XxxData} 里，与使用它的 {@code of()} / {@code set()}
 * 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化）。
 */
public final class SpongeComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活海绵所存流体（{fluid, mmB}，湿/干与所含流体的唯一权威）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingSpongeData>> LIVING_SPONGE_DATA =
        REG.register("living_sponge_data", () ->
            DataComponentType.<LivingSpongeData>builder()
                .persistent(LivingSpongeData.CODEC)
                .networkSynchronized(LivingSpongeData.STREAM_CODEC)
                .build());

    private SpongeComponents() {}
}
