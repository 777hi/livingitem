package com.qiqi.li.living.transfer;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;
import com.qiqi.li.living.domain.furnace.LivingFurnaceData;
import com.qiqi.li.living.domain.hopper.LivingHopperData;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.domain.tnt.LivingTntData;
import com.qiqi.li.living.domain.water.LivingWaterBucketData;
import com.qiqi.li.living.domain.water.LivingWaterWheelData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.power.PhaseSnapshot;
import com.qiqi.li.living.domain.ender.LivingEnderChestData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneTorchData;
import com.qiqi.li.living.domain.redstone.LivingButtonData;
import com.qiqi.li.living.domain.redstone.LivingLeverData;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import com.qiqi.li.living.domain.redstone.LivingRedstoneLampData;
import com.qiqi.li.living.domain.redstone.LivingRepeaterData;
import com.qiqi.li.living.domain.redstone.LivingComparatorData;
import com.qiqi.li.living.domain.redstone.LivingCutCopperData;
import com.qiqi.li.living.domain.redstone.LivingGrateData;
import com.qiqi.li.living.domain.redstone.LivingCopperBulbData;
import com.qiqi.li.living.domain.redstone.LivingCopperSignalData;
import com.qiqi.li.living.domain.power.LivingWaxedChiseledData;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData;
import com.qiqi.li.living.domain.power.LivingWaxedBulbData;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolProgress;
import com.qiqi.li.living.domain.tools.LivingToolAction;

/**
 * 全部持久化类型的注册站（A1 迁移，2026-09-28）—— 原散在 LivingItemManager（api 包）
 * 的组件/附件常量整体迁此，使 api 面不再牵出 domain 类型。
 *
 * <p>本类是<b>纯注册站</b>：每个常量 2 行（机制要求 DeferredRegister 集中挂总线），
 * 无逻辑、不随功能增长。读写数据的便捷访问器在各 Data 类自己的 of() / set()；
 * 框架级组件（IS_LIVING、工具 owner 等原始类型组件）仍留在 LivingItemManager。</p>
 *
 * <p>⚠️ 为什么常量集中在一个类：静态初始化顺序由 JVM 决定、无法声明 ——
 * 一个类一个 &lt;clinit&gt; 原子完成，从结构上消灭注册时序问题。</p>
 */
public final class LivingComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, com.qiqi.li.LivingItem.MOD_ID);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, com.qiqi.li.LivingItem.MOD_ID);

    private LivingComponents() {}

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> IS_LIVING =
            DATA_COMPONENT_TYPES.register("is_living", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> LIVING_TOOL_OWNER =
            DATA_COMPONENT_TYPES.register("living_tool_owner", () ->
                    DataComponentType.<UUID>builder()
                            .persistent(UUIDUtil.CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> LIVING_TOOL_DIG_TICKS =
            DATA_COMPONENT_TYPES.register("living_tool_dig_ticks", () ->
                    DataComponentType.<Integer>builder()
                            .networkSynchronized(ByteBufCodecs.VAR_INT)
                            .build());

    /** 熔炉燃烧标志：燃烧状态翻转时写入，供客户端图标谓词（active/idle）读取。派生数据不落盘（仅网络同步，MAP_POST_PROCESSING 先例） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LIVING_FURNACE_BURNING =
            DATA_COMPONENT_TYPES.register("living_furnace_burning", () ->
                    DataComponentType.<Boolean>builder()
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LIVING_FARMLAND_MOIST =
            DATA_COMPONENT_TYPES.register("living_farmland_moist", () ->
                    DataComponentType.<Boolean>builder()
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingFurnaceData>> LIVING_FURNACE_DATA =
            DATA_COMPONENT_TYPES.register("living_furnace_data", () ->
                    DataComponentType.<LivingFurnaceData>builder()
                            .persistent(LivingFurnaceData.CODEC)
                            .networkSynchronized(LivingFurnaceData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingHopperData>> LIVING_HOPPER_DATA =
            DATA_COMPONENT_TYPES.register("living_hopper_data", () ->
                    DataComponentType.<LivingHopperData>builder()
                            .persistent(LivingHopperData.CODEC)
                            .networkSynchronized(LivingHopperData.STREAM_CODEC)
                            .build());

    /** 漏斗黑白名单过滤链：容器派生数据不落盘（仅网络同步供 tooltip，MAP_POST_PROCESSING 先例），每 tick 由快照重建 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.qiqi.li.living.transfer.FilterData>> LIVING_HOPPER_FILTER =
            DATA_COMPONENT_TYPES.register("living_hopper_filter", () ->
                    DataComponentType.<com.qiqi.li.living.transfer.FilterData>builder()
                            .networkSynchronized(com.qiqi.li.living.transfer.FilterData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingTntData>> LIVING_TNT_DATA =
            DATA_COMPONENT_TYPES.register("living_tnt_data", () ->
                    DataComponentType.<LivingTntData>builder()
                            .persistent(LivingTntData.CODEC)
                            .networkSynchronized(LivingTntData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaterBucketData>> LIVING_WATER_BUCKET_DATA =
            DATA_COMPONENT_TYPES.register("living_water_bucket_data", () ->
                    DataComponentType.<LivingWaterBucketData>builder()
                            .persistent(LivingWaterBucketData.CODEC)
                            .networkSynchronized(LivingWaterBucketData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingWaterWheelData>> LIVING_WATER_WHEEL_DATA =
            DATA_COMPONENT_TYPES.register("living_water_wheel_data", () ->
                    DataComponentType.<LivingWaterWheelData>builder()
                            .persistent(LivingWaterWheelData.CODEC)
                            .networkSynchronized(LivingWaterWheelData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingEnderChestData>> LIVING_ENDER_CHEST_DATA =
            DATA_COMPONENT_TYPES.register("living_ender_chest_data", () ->
                    DataComponentType.<LivingEnderChestData>builder()
                            .persistent(LivingEnderChestData.CODEC)
                            .networkSynchronized(LivingEnderChestData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRedstoneData>> LIVING_REDSTONE_DATA =
            DATA_COMPONENT_TYPES.register("living_redstone_data", () ->
                    DataComponentType.<LivingRedstoneData>builder()
                            .persistent(LivingRedstoneData.CODEC)
                            .networkSynchronized(LivingRedstoneData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRedstoneTorchData>> LIVING_REDSTONE_TORCH_DATA =
            DATA_COMPONENT_TYPES.register("living_redstone_torch_data", () ->
                    DataComponentType.<LivingRedstoneTorchData>builder()
                            .persistent(LivingRedstoneTorchData.CODEC)
                            .networkSynchronized(LivingRedstoneTorchData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingButtonData>> LIVING_BUTTON_DATA =
            DATA_COMPONENT_TYPES.register("living_button_data", () ->
                    DataComponentType.<LivingButtonData>builder()
                            .persistent(LivingButtonData.CODEC)
                            .networkSynchronized(LivingButtonData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingLeverData>> LIVING_LEVER_DATA =
            DATA_COMPONENT_TYPES.register("living_lever_data", () ->
                    DataComponentType.<LivingLeverData>builder()
                            .persistent(LivingLeverData.CODEC)
                            .networkSynchronized(LivingLeverData.STREAM_CODEC)
                            .build());

    /** 活耕地种植数据（作物类型标记 + 生长阶段 + round-robin 产出状态，客户端渲染数据源） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FarmlandPlantComponent>> FARMLAND_PLANT =
            DATA_COMPONENT_TYPES.register("farmland_plant", () ->
                    DataComponentType.<FarmlandPlantComponent>builder()
                            .persistent(FarmlandPlantComponent.CODEC)
                            .networkSynchronized(FarmlandPlantComponent.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRedstoneLampData>> LIVING_REDSTONE_LAMP_DATA =
            DATA_COMPONENT_TYPES.register("living_redstone_lamp_data", () ->
                    DataComponentType.<LivingRedstoneLampData>builder()
                            .persistent(LivingRedstoneLampData.CODEC)
                            .networkSynchronized(LivingRedstoneLampData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingRepeaterData>> LIVING_REPEATER_DATA =
            DATA_COMPONENT_TYPES.register("living_repeater_data", () ->
                    DataComponentType.<LivingRepeaterData>builder()
                            .persistent(LivingRepeaterData.CODEC)
                            .networkSynchronized(LivingRepeaterData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingComparatorData>> LIVING_COMPARATOR_DATA =
            DATA_COMPONENT_TYPES.register("living_comparator_data", () ->
                    DataComponentType.<LivingComparatorData>builder()
                            .persistent(LivingComparatorData.CODEC)
                            .networkSynchronized(LivingComparatorData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingCutCopperData>> LIVING_CUT_COPPER_DATA =
            DATA_COMPONENT_TYPES.register("living_cut_copper_data", () ->
                    DataComponentType.<LivingCutCopperData>builder()
                            .persistent(LivingCutCopperData.CODEC)
                            .networkSynchronized(LivingCutCopperData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingGrateData>> LIVING_GRATE_DATA =
            DATA_COMPONENT_TYPES.register("living_grate_data", () ->
                    DataComponentType.<LivingGrateData>builder()
                            .persistent(LivingGrateData.CODEC)
                            .networkSynchronized(LivingGrateData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingCopperBulbData>> LIVING_COPPER_BULB_DATA =
            DATA_COMPONENT_TYPES.register("living_copper_bulb_data", () ->
                    DataComponentType.<LivingCopperBulbData>builder()
                            .persistent(LivingCopperBulbData.CODEC)
                            .networkSynchronized(LivingCopperBulbData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingCopperSignalData>> LIVING_COPPER_SIGNAL =
            DATA_COMPONENT_TYPES.register("living_copper_signal", () ->
                    DataComponentType.<LivingCopperSignalData>builder()
                            .persistent(LivingCopperSignalData.CODEC)
                            .networkSynchronized(LivingCopperSignalData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.qiqi.li.living.domain.power.LivingWaxedChiseledData>> LIVING_WAXED_CHISELED_DATA =
            DATA_COMPONENT_TYPES.register("living_waxed_chiseled_data", () ->
                    DataComponentType.<com.qiqi.li.living.domain.power.LivingWaxedChiseledData>builder()
                            .persistent(com.qiqi.li.living.domain.power.LivingWaxedChiseledData.CODEC)
                            .networkSynchronized(com.qiqi.li.living.domain.power.LivingWaxedChiseledData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.qiqi.li.living.domain.power.LivingWaxedGeneratorData>> LIVING_GENERATOR_DATA =
            DATA_COMPONENT_TYPES.register("living_generator_data", () ->
                    DataComponentType.<com.qiqi.li.living.domain.power.LivingWaxedGeneratorData>builder()
                            .persistent(com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.CODEC)
                            .networkSynchronized(com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.qiqi.li.living.domain.power.LivingWaxedBulbData>> LIVING_WAXED_BULB_DATA =
            DATA_COMPONENT_TYPES.register("living_waxed_bulb_data", () ->
                    DataComponentType.<com.qiqi.li.living.domain.power.LivingWaxedBulbData>builder()
                            .persistent(com.qiqi.li.living.domain.power.LivingWaxedBulbData.CODEC)
                            .networkSynchronized(com.qiqi.li.living.domain.power.LivingWaxedBulbData.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolMemory>> LIVING_TOOL_MEMORY =
            DATA_COMPONENT_TYPES.register("living_tool_memory", () ->
                    DataComponentType.<LivingToolMemory>builder()
                            .persistent(LivingToolMemory.CODEC)
                            .networkSynchronized(LivingToolMemory.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolProgress>> LIVING_TOOL_PROGRESS =
            DATA_COMPONENT_TYPES.register("living_tool_progress", () ->
                    DataComponentType.<LivingToolProgress>builder()
                            .persistent(LivingToolProgress.CODEC)
                            .networkSynchronized(LivingToolProgress.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolAction>> LIVING_TOOL_LAST_ACTION =
            DATA_COMPONENT_TYPES.register("living_tool_last_action", () ->
                    DataComponentType.<LivingToolAction>builder()
                            .networkSynchronized(LivingToolAction.STREAM_CODEC)
                            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerStressData>> CONTAINER_STRESS_DATA =
            ATTACHMENT_TYPES.register("container_stress_data", () ->
                    AttachmentType.builder(() -> ContainerStressData.EMPTY).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerFluidData>> CONTAINER_FLUID_DATA =
            ATTACHMENT_TYPES.register("container_fluid_data", () ->
                    AttachmentType.builder(() -> ContainerFluidData.EMPTY).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.qiqi.li.living.domain.power.PhaseSnapshot>> CONTAINER_PHASE_SNAPSHOT =
            ATTACHMENT_TYPES.register("container_phase_snapshot", () ->
                    AttachmentType.builder(() -> com.qiqi.li.living.domain.power.PhaseSnapshot.EMPTY)
                            .serialize(com.qiqi.li.living.domain.power.PhaseSnapshot.CODEC)
                            .build());

}
