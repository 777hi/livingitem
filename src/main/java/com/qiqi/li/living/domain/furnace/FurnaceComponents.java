package com.qiqi.li.living.domain.furnace;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 活熔炉领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class FurnaceComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活熔炉数据（进度 / 燃料 / 配方）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingFurnaceData>> LIVING_FURNACE_DATA =
        REG.register("living_furnace_data", () ->
            DataComponentType.<LivingFurnaceData>builder()
                .persistent(LivingFurnaceData.CODEC)
                .networkSynchronized(LivingFurnaceData.STREAM_CODEC)
                .build());

    /** 熔炉燃烧标志：燃烧状态翻转时写入，供客户端图标谓词（active/idle）读取。派生数据不落盘（仅网络同步）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LIVING_FURNACE_BURNING =
        REG.register("living_furnace_burning", () ->
            DataComponentType.<Boolean>builder()
                .networkSynchronized(ByteBufCodecs.BOOL)
                .build());

    private FurnaceComponents() {}

    /** 活熔炉是否在燃烧（派生标志，仅网络同步；图标 active/idle 变体数据源）。 */
    public static boolean isBurning(ItemStack stack) {
        Boolean burning = stack.get(LIVING_FURNACE_BURNING.value());
        return burning != null && burning;
    }

    /** 设置燃烧标志（true 写入，false 移除）。 */
    public static void setBurning(ItemStack stack, boolean burning) {
        if (burning) {
            stack.set(LIVING_FURNACE_BURNING.value(), true);
        } else {
            stack.remove(LIVING_FURNACE_BURNING.value());
        }
    }
}
