package com.qiqi.li.living.domain.farmland;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活耕地领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class FarmlandComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活耕地种植数据（作物类型标记 + 生长阶段 + round-robin 产出状态，客户端渲染数据源）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FarmlandPlantComponent>> FARMLAND_PLANT =
        REG.register("farmland_plant", () ->
            DataComponentType.<FarmlandPlantComponent>builder()
                .persistent(FarmlandPlantComponent.CODEC)
                .networkSynchronized(FarmlandPlantComponent.STREAM_CODEC)
                .build());

    /** 活耕地湿润标志（派生数据，仅网络同步）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LIVING_FARMLAND_MOIST =
        REG.register("living_farmland_moist", () ->
            DataComponentType.<Boolean>builder()
                .networkSynchronized(ByteBufCodecs.BOOL)
                .build());

    private FarmlandComponents() {}

    /** 活耕地湿润标志（未打标志 = 干燥；图标 moist/dry 变体切换数据源）。 */
    public static boolean isMoist(ItemStack stack) {
        return LivingItemManager.getData(stack, LIVING_FARMLAND_MOIST.value(), Boolean.FALSE);
    }

    /** 设置湿润标志。 */
    public static void setMoist(ItemStack stack, boolean moist) {
        LivingItemManager.setData(stack, LIVING_FARMLAND_MOIST.value(), moist, Boolean.FALSE);
    }
}
