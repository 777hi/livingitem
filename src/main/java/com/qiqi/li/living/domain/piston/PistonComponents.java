package com.qiqi.li.living.domain.piston;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活活塞领域的组件注册总线（与 {@code RedstoneComponents} 等域总线同款：本类只放总线，
 * 组件定义在 {@code LivingPistonData} 里）。2026-11-03 活活塞自 {@code domain/redstone/piston}
 * 独立成域：它是信号触发的执行件（同活 TNT 的归属判据），不是信号元件。
 */
public final class PistonComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活活塞方向配置；机械伸出态不落物品组件。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingPistonData>> LIVING_PISTON_DATA =
        REG.register("living_piston_data", () ->
            DataComponentType.<LivingPistonData>builder()
                .persistent(LivingPistonData.CODEC)
                .networkSynchronized(LivingPistonData.STREAM_CODEC)
                .build());

    private PistonComponents() {}
}
