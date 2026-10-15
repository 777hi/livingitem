package com.qiqi.li.living.domain.beacon;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活信标领域的组件注册总线（照 {@code FurnaceComponents} 模式）。
 */
public final class BeaconComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 效果配置（5 矿石槽）—— 持久 + 网络同步（喂锭交互后需同步给开箱玩家）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BeaconConfig>> BEACON_CONFIG =
        REG.register("living_beacon_config", () ->
            DataComponentType.<BeaconConfig>builder()
                .persistent(BeaconConfig.CODEC)
                .networkSynchronized(BeaconConfig.STREAM_CODEC)
                .build());

    private BeaconComponents() {
    }
}
