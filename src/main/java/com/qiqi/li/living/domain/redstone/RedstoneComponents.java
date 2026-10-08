package com.qiqi.li.living.domain.redstone;

import com.qiqi.li.living.api.LivingMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 红石领域的组件注册总线（2026-10-08：组件定义从 {@code LivingComponents} 拆回本领域）。
 *
 * <p>本类<b>只放总线</b> —— 具体组件定义在各 {@code XxxData} 类里，与使用它的
 * {@code of()} / {@code set()} 同属一个 {@code <clinit>} ⇒ 从结构上避免循环静态初始化。</p>
 */
public final class RedstoneComponents {

    public static final DeferredRegister<DataComponentType<?>> REG =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LivingMod.ID);

    /** 活红石粉数据（信号层账本的物品侧镜像）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRedstoneData>> LIVING_REDSTONE_DATA =
        REG.register("living_redstone_data", () ->
            DataComponentType.<LivingRedstoneData>builder()
                .persistent(LivingRedstoneData.CODEC)
                .networkSynchronized(LivingRedstoneData.STREAM_CODEC)
                .build());

    /** 活红石火把数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRedstoneTorchData>> LIVING_REDSTONE_TORCH_DATA =
        REG.register("living_redstone_torch_data", () ->
            DataComponentType.<LivingRedstoneTorchData>builder()
                .persistent(LivingRedstoneTorchData.CODEC)
                .networkSynchronized(LivingRedstoneTorchData.STREAM_CODEC)
                .build());

    /** 活红石灯数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRedstoneLampData>> LIVING_REDSTONE_LAMP_DATA =
        REG.register("living_redstone_lamp_data", () ->
            DataComponentType.<LivingRedstoneLampData>builder()
                .persistent(LivingRedstoneLampData.CODEC)
                .networkSynchronized(LivingRedstoneLampData.STREAM_CODEC)
                .build());

    /** 活按钮数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingButtonData>> LIVING_BUTTON_DATA =
        REG.register("living_button_data", () ->
            DataComponentType.<LivingButtonData>builder()
                .persistent(LivingButtonData.CODEC)
                .networkSynchronized(LivingButtonData.STREAM_CODEC)
                .build());

    /** 活拉杆数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingLeverData>> LIVING_LEVER_DATA =
        REG.register("living_lever_data", () ->
            DataComponentType.<LivingLeverData>builder()
                .persistent(LivingLeverData.CODEC)
                .networkSynchronized(LivingLeverData.STREAM_CODEC)
                .build());

    /** 活中继器数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRepeaterData>> LIVING_REPEATER_DATA =
        REG.register("living_repeater_data", () ->
            DataComponentType.<LivingRepeaterData>builder()
                .persistent(LivingRepeaterData.CODEC)
                .networkSynchronized(LivingRepeaterData.STREAM_CODEC)
                .build());

    /** 活比较器数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingComparatorData>> LIVING_COMPARATOR_DATA =
        REG.register("living_comparator_data", () ->
            DataComponentType.<LivingComparatorData>builder()
                .persistent(LivingComparatorData.CODEC)
                .networkSynchronized(LivingComparatorData.STREAM_CODEC)
                .build());

    /** 活切制铜块数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingCutCopperData>> LIVING_CUT_COPPER_DATA =
        REG.register("living_cut_copper_data", () ->
            DataComponentType.<LivingCutCopperData>builder()
                .persistent(LivingCutCopperData.CODEC)
                .networkSynchronized(LivingCutCopperData.STREAM_CODEC)
                .build());

    /** 活铜格栅数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingGrateData>> LIVING_GRATE_DATA =
        REG.register("living_grate_data", () ->
            DataComponentType.<LivingGrateData>builder()
                .persistent(LivingGrateData.CODEC)
                .networkSynchronized(LivingGrateData.STREAM_CODEC)
                .build());

    /** 活铜灯数据。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingCopperBulbData>> LIVING_COPPER_BULB_DATA =
        REG.register("living_copper_bulb_data", () ->
            DataComponentType.<LivingCopperBulbData>builder()
                .persistent(LivingCopperBulbData.CODEC)
                .networkSynchronized(LivingCopperBulbData.STREAM_CODEC)
                .build());

    /** 活铜信号数据（氧化级传导）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingCopperSignalData>> LIVING_COPPER_SIGNAL =
        REG.register("living_copper_signal", () ->
            DataComponentType.<LivingCopperSignalData>builder()
                .persistent(LivingCopperSignalData.CODEC)
                .networkSynchronized(LivingCopperSignalData.STREAM_CODEC)
                .build());

    private RedstoneComponents() {}
}
