package com.qiqi.li.living.domain.brewing;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活酿造台领域的组件注册总线。
 *
 * <p>与 {@code FurnaceComponents} 同构：本类<b>只放总线</b>，具体组件定义与使用它的
 * {@code of()} / {@code set()} 同属 {@link LivingBrewingStandData} 的 {@code <clinit>}
 * ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class BrewingStandComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /**
     * 活酿造台数据（当前 = 槽位方向；燃料次数在第二段加入）。
     *
     * <p>方向是<b>持久状态</b>，故 {@code persistent} + {@code networkSynchronized}；
     * 酿造进度 / 当前材料等瞬态数据走运行时缓存，不进本组件（不影响物品堆叠）。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingBrewingStandData>> LIVING_BREWING_STAND_DATA =
        REG.register("living_brewing_stand_data", () ->
            DataComponentType.<LivingBrewingStandData>builder()
                .persistent(LivingBrewingStandData.CODEC)
                .networkSynchronized(LivingBrewingStandData.STREAM_CODEC)
                .build());

    private BrewingStandComponents() {}
}
