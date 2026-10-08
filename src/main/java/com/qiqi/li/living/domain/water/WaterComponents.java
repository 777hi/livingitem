package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.api.LivingMod;

import java.util.Map;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 活水领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class WaterComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 附件总线（容器级流体 / 应力数据落盘用）。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACH_REG =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LivingMod.ID);

    /** 活水车数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaterWheelData>> LIVING_WATER_WHEEL_DATA =
        REG.register("living_water_wheel_data", () ->
            DataComponentType.<LivingWaterWheelData>builder()
                .persistent(LivingWaterWheelData.CODEC)
                .networkSynchronized(LivingWaterWheelData.STREAM_CODEC)
                .build());

    /** 容器应力（tick 级数据的落盘附件）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerStressData>> CONTAINER_STRESS_DATA =
        ATTACH_REG.register("container_stress_data", () ->
            AttachmentType.builder(() -> ContainerStressData.EMPTY).build());

    /** 容器级流体数据（BE 附件，跨存档持久）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerFluidData>> CONTAINER_FLUID_DATA =
        ATTACH_REG.register("container_fluid_data", () ->
            AttachmentType.builder(() -> ContainerFluidData.EMPTY)
                .serialize(ContainerFluidData.CODEC)
                .build());

    /**
     * 玩家背包 / 末影箱的容器级流体数据（B.5 第三项，2026-10-04）。
     *
     * <p>背包与末影箱<b>没有 BE</b> 可挂 {@link #CONTAINER_FLUID_DATA} ⇒ 落到 <b>Player</b> 上；
     * 一个玩家有背包 + 末影箱<b>两个</b>容器 ⇒ 用「容器键 → 流体数据」映射。默认空 map；
     * {@code copyOnDeath} 默认 false ⇒ 玩家死亡清空（与「背包清空」语义一致）。</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<String, ContainerFluidData>>> CONTAINER_FLUID_DATA_PLAYER =
        ATTACH_REG.register("container_fluid_data_player", () ->
            AttachmentType.<Map<String, ContainerFluidData>>builder(() -> Map.of())
                .serialize(ContainerFluidData.KEYED_CODEC)
                .build());

    private WaterComponents() {}
}
