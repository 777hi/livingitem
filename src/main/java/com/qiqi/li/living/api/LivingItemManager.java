package com.qiqi.li.living.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import java.util.Set;

import com.qiqi.li.living.domain.furnace.LivingFurnaceData;
import com.qiqi.li.living.domain.hopper.LivingHopperData;
import com.qiqi.li.living.domain.tnt.LivingTntData;
import com.qiqi.li.living.domain.water.LivingWaterBucketData;
import com.qiqi.li.living.domain.water.LivingWaterWheelData;
import com.qiqi.li.living.domain.ender.LivingEnderChestData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneTorchData;
import com.qiqi.li.living.domain.redstone.LivingButtonData;
import com.qiqi.li.living.domain.redstone.LivingLeverData;
import com.qiqi.li.living.domain.redstone.LivingRedstoneLampData;
import com.qiqi.li.living.domain.redstone.LivingRepeaterData;
import com.qiqi.li.living.domain.redstone.LivingComparatorData;
import com.qiqi.li.living.domain.redstone.LivingCutCopperData;
import com.qiqi.li.living.domain.redstone.LivingGrateData;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolProgress;
import com.qiqi.li.living.domain.redstone.LivingCopperBulbData;
import com.qiqi.li.living.domain.redstone.LivingCopperSignalData;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 活物品管理器。
 * 负责：
 * 1. 注册自定义 DataComponent 类型（IS_LIVING 及各功能 DataComponent）
 * 2. 管理已注册的活物品功能（LivingItemFunction 列表）
 * 3. 提供泛型数据访问方法（getData/setData）
 */
public class LivingItemManager {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, com.qiqi.li.LivingItem.MOD_ID);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, com.qiqi.li.LivingItem.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerStressData>> CONTAINER_STRESS_DATA =
            ATTACHMENT_TYPES.register("container_stress_data", () ->
                    AttachmentType.builder(() -> ContainerStressData.EMPTY).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerFluidData>> CONTAINER_FLUID_DATA =
            ATTACHMENT_TYPES.register("container_fluid_data", () ->
                    AttachmentType.builder(() -> ContainerFluidData.EMPTY).build());

    /**
     * 相位快照（2026-09-09 落盘）：红电相位账本的跨会话持久化。
     * 与流体/应力附件不同，这个带 {@code serialize(Codec)}——真正写入存档，
     * 退出重进 / 区块卸载超时后锁相状态无缝续接（详见 PhaseSnapshot javadoc）。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.qiqi.li.living.domain.power.PhaseSnapshot>> CONTAINER_PHASE_SNAPSHOT =
            ATTACHMENT_TYPES.register("container_phase_snapshot", () ->
                    AttachmentType.builder(() -> com.qiqi.li.living.domain.power.PhaseSnapshot.EMPTY)
                            .serialize(com.qiqi.li.living.domain.power.PhaseSnapshot.CODEC)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> IS_LIVING =
            DATA_COMPONENT_TYPES.register("is_living", () ->
                    DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingFurnaceData>> LIVING_FURNACE_DATA =
            DATA_COMPONENT_TYPES.register("living_furnace_data", () ->
                    DataComponentType.<LivingFurnaceData>builder()
                            .persistent(LivingFurnaceData.CODEC)
                            .networkSynchronized(LivingFurnaceData.STREAM_CODEC)
                            .build());

    /** 熔炉燃烧标志：燃烧状态翻转时写入，供客户端图标谓词（active/idle）读取。派生数据不落盘（仅网络同步，MAP_POST_PROCESSING 先例） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LIVING_FURNACE_BURNING =
            DATA_COMPONENT_TYPES.register("living_furnace_burning", () ->
                    DataComponentType.<Boolean>builder()
                            .networkSynchronized(ByteBufCodecs.BOOL)
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

    /**
     * 活耕地湿润标志（图标 moist/dry 变体切换数据源）。
     * tick 在湿润状态翻转时写标志 + syncSlotToClients（稳态零写入零同步）；
     * 进 {@link LivingItemFunction#getIgnoredComponentTypes}（湿润/干燥耕地可堆叠），
     * 参考熔炉燃烧标志 LIVING_FURNACE_BURNING 先例。
     * 派生数据不落盘（仅网络同步）：湿润度每 tick 从流体邻接重算，持久化无正确性价值。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LIVING_FARMLAND_MOIST =
            DATA_COMPONENT_TYPES.register("living_farmland_moist", () ->
                    DataComponentType.<Boolean>builder()
                            .networkSynchronized(ByteBufCodecs.BOOL)
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

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.qiqi.li.living.domain.power.LivingWaxedCutData>> LIVING_WAXED_CUT_DATA =
            DATA_COMPONENT_TYPES.register("living_waxed_cut_data", () ->
                    DataComponentType.<com.qiqi.li.living.domain.power.LivingWaxedCutData>builder()
                            .persistent(com.qiqi.li.living.domain.power.LivingWaxedCutData.CODEC)
                            .networkSynchronized(com.qiqi.li.living.domain.power.LivingWaxedCutData.STREAM_CODEC)
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

    /**
     * 活工具记忆（挖掘记忆 + 交互记忆）。
     *
     * <p>必须网络同步：客户端要据此<b>本地重算射线</b>来渲染悬浮模型与动画（{@code K30}），
     * 服务端因此无需同步"命中了哪个方块"。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolMemory>> LIVING_TOOL_MEMORY =
            DATA_COMPONENT_TYPES.register("living_tool_memory", () ->
                    DataComponentType.<LivingToolMemory>builder()
                            .persistent(LivingToolMemory.CODEC)
                            .networkSynchronized(LivingToolMemory.STREAM_CODEC)
                            .build());

    /**
     * 活工具挖掘进度（{@code L21}）：当前正在挖的目标 + 世界轴起始 tick。
     *
     * <p>组件缺失 = 没在挖。只在<b>开始挖时写一次</b>，之后每 tick 重算进度，不写组件。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingToolProgress>> LIVING_TOOL_PROGRESS =
            DATA_COMPONENT_TYPES.register("living_tool_progress", () ->
                    DataComponentType.<LivingToolProgress>builder()
                            .persistent(LivingToolProgress.CODEC)
                            .networkSynchronized(LivingToolProgress.STREAM_CODEC)
                            .build());

    /**
     * 活工具主人 UUID（{@code L25}）。
     *
     * <p>用途：回放时 FakePlayer 用它伪装成真实玩家，以通过领地 / 保护插件的权限判定
     * （Create 的 {@code DeployerGameProfile}、Mekanism 同款技巧 —— 覆写
     * {@code GameProfile#getId()} 返回主人 UUID）。</p>
     *
     * <ul>
     *   <li>玩家手动活化 → 记录该玩家 UUID</li>
     *   <li>未来「自动活化物品的活物品」→ 可写入自定义 UUID</li>
     *   <li><b>组件缺失（null）= 无主人</b> → 回退到通用 FakePlayer</li>
     * </ul>
     *
     * <p>服务端专用，<b>不需要</b>网络同步（客户端不参与回放）。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> LIVING_TOOL_OWNER =
            DATA_COMPONENT_TYPES.register("living_tool_owner", () ->
                    DataComponentType.<UUID>builder()
                            .persistent(UUIDUtil.CODEC)
                            .build());

    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();
    private static final List<LivingItemFunction> FUNCTIONS_VIEW = Collections.unmodifiableList(FUNCTIONS);
    private static final Map<Item, List<LivingItemFunction>> APPLICABLE_CACHE = new ConcurrentHashMap<>();

    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering living item function: {}", function.getFunctionId());
        FUNCTIONS.add(function);
        APPLICABLE_CACHE.clear();
    }

    public static List<LivingItemFunction> getAllFunctions() {
        return FUNCTIONS_VIEW;
    }

    public static boolean isLivingItem(ItemStack stack) {
        return !stack.isEmpty() && stack.has(IS_LIVING.value());
    }

    public static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && isLivingItem(stack);
    }

    public static List<LivingItemFunction> getApplicableFunctions(ItemStack stack) {
        if (!isLivingItem(stack)) return List.of();

        Item item = stack.getItem();
        List<LivingItemFunction> cached = APPLICABLE_CACHE.get(item);
        if (cached != null) return cached;

        List<LivingItemFunction> applicable = new ArrayList<>();
        for (LivingItemFunction function : FUNCTIONS) {
            if (function.canApply(stack)) {
                applicable.add(function);
            }
        }
        List<LivingItemFunction> result = Collections.unmodifiableList(applicable);
        APPLICABLE_CACHE.put(item, result);
        return result;
    }

    public static Set<DataComponentType<?>> getIgnoredComponentTypes(ItemStack stack) {
        if (!isLivingItem(stack)) return Set.of();

        Set<DataComponentType<?>> types = new HashSet<>();
        for (LivingItemFunction func : getApplicableFunctions(stack)) {
            types.addAll(func.getIgnoredComponentTypes());
        }
        return types;
    }

    /**
     * 清除活物品的所有功能数据。
     *
     * <p><b>注意：</b>新增 {@link LivingItemFunction} 时，如果该功能有自己的
     * {@link net.minecraft.core.component.DataComponentType}，必须在此方法中
     * 添加对应的 {@code stack.remove()} 调用，否则旧数据会残留在物品上。</p>
     */
    public static void clearLivingData(ItemStack stack) {
        stack.remove(IS_LIVING.value());
        stack.remove(LIVING_TNT_DATA.value());
        stack.remove(LIVING_WATER_BUCKET_DATA.value());
        stack.remove(LIVING_WATER_WHEEL_DATA.value());
        stack.remove(LIVING_FURNACE_DATA.value());
        stack.remove(LIVING_FURNACE_BURNING.value());
        stack.remove(LIVING_HOPPER_DATA.value());
        stack.remove(LIVING_HOPPER_FILTER.value());
        stack.remove(LIVING_ENDER_CHEST_DATA.value());
        stack.remove(LIVING_REDSTONE_DATA.value());
        stack.remove(LIVING_REDSTONE_TORCH_DATA.value());
        stack.remove(LIVING_BUTTON_DATA.value());
        stack.remove(LIVING_LEVER_DATA.value());
        stack.remove(LIVING_REDSTONE_LAMP_DATA.value());
        stack.remove(LIVING_REPEATER_DATA.value());
        stack.remove(LIVING_COMPARATOR_DATA.value());
        stack.remove(LIVING_CUT_COPPER_DATA.value());
        stack.remove(LIVING_GRATE_DATA.value());
        stack.remove(LIVING_COPPER_BULB_DATA.value());
        stack.remove(LIVING_COPPER_SIGNAL.value());
        stack.remove(LIVING_WAXED_CUT_DATA.value());
        stack.remove(LIVING_WAXED_CHISELED_DATA.value());
        stack.remove(LIVING_GENERATOR_DATA.value());
        stack.remove(LIVING_WAXED_BULB_DATA.value());
        stack.remove(FARMLAND_PLANT.value());
        stack.remove(LIVING_FARMLAND_MOIST.value());
        stack.remove(LIVING_TOOL_MEMORY.value());
        stack.remove(LIVING_TOOL_PROGRESS.value());
        stack.remove(LIVING_TOOL_OWNER.value());
    }

    /** 切换活化状态（不记录主人）。 */
    public static void setLiving(ItemStack stack, boolean living) {
        setLiving(stack, living, null);
    }

    /**
     * 切换活化状态。
     *
     * @param owner 主人 UUID（活工具用，见 {@link #LIVING_TOOL_OWNER}）；null = 不记录
     */
    public static void setLiving(ItemStack stack, boolean living, @Nullable UUID owner) {
        if (living) {
            stack.set(IS_LIVING.value(), true);
            if (owner != null) {
                setToolOwner(stack, owner);
            }
            if (stack.is(Items.CHEST) && !stack.has(net.minecraft.core.component.DataComponents.CONTAINER)) {
                stack.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.EMPTY);
            }
            if (stack.is(Items.FURNACE)) {
                setData(stack, LIVING_FURNACE_DATA.value(), LivingFurnaceData.DEFAULT, LivingFurnaceData.DEFAULT);
            }
        } else {
            clearLivingData(stack);
        }
    }

    /**
     * 泛型 getter —— 替代所有 getXxxData 方法。
     *
     * @param stack 物品
     * @param type DataComponent 类型
     * @param defaultValue 默认值（当物品上没有该组件时返回）
     * @param <T> 数据类型
     * @return 组件数据，或默认值
     */
    public static <T> T getData(ItemStack stack, DataComponentType<T> type, T defaultValue) {
        T data = stack.get(type);
        return data != null ? data : defaultValue;
    }

    /**
     * 泛型 setter —— 替代所有 setXxxData 方法。
     * 当数据等于默认值时自动移除组件，节省 NBT 空间。
     *
     * @param stack 物品
     * @param type DataComponent 类型
     * @param data 要设置的数据
     * @param defaultValue 默认值（用于判断是否移除组件）
     * @param <T> 数据类型
     */
    public static <T> void setData(ItemStack stack, DataComponentType<T> type, T data, T defaultValue) {
        if (Objects.equals(data, defaultValue)) {
            stack.remove(type);
        } else {
            stack.set(type, data);
        }
    }

    /**
     * 便捷方法：获取熔炉数据。
     */
    public static LivingFurnaceData getFurnaceData(ItemStack stack) {
        return getData(stack, LIVING_FURNACE_DATA.value(), LivingFurnaceData.DEFAULT);
    }

    /**
     * 便捷方法：设置熔炉数据。
     */
    public static void setFurnaceData(ItemStack stack, LivingFurnaceData data) {
        setData(stack, LIVING_FURNACE_DATA.value(), data, LivingFurnaceData.DEFAULT);
    }

    /**
     * 便捷方法：读取熔炉燃烧标志（供图标谓词使用）。
     */
    public static boolean isFurnaceBurning(ItemStack stack) {
        Boolean burning = stack.get(LIVING_FURNACE_BURNING.value());
        return burning != null && burning;
    }

    /**
     * 便捷方法：写入熔炉燃烧标志（false 时移除组件，节省 NBT）。
     */
    public static void setFurnaceBurning(ItemStack stack, boolean burning) {
        if (burning) {
            stack.set(LIVING_FURNACE_BURNING.value(), true);
        } else {
            stack.remove(LIVING_FURNACE_BURNING.value());
        }
    }

    /**
     * 便捷方法：获取漏斗数据。
     */
    public static LivingHopperData getHopperData(ItemStack stack) {
        return getData(stack, LIVING_HOPPER_DATA.value(), LivingHopperData.DEFAULT);
    }

    /**
     * 便捷方法：设置漏斗数据。
     */
    public static void setHopperData(ItemStack stack, LivingHopperData data) {
        setData(stack, LIVING_HOPPER_DATA.value(), data, LivingHopperData.DEFAULT);
    }

    /**
     * 便捷方法：读取漏斗过滤链（tooltip 展示用；规则本体由 HopperFilterBuilder 每 tick 派生）。
     */
    public static com.qiqi.li.living.transfer.FilterData getHopperFilter(ItemStack stack) {
        return getData(stack, LIVING_HOPPER_FILTER.value(), com.qiqi.li.living.transfer.FilterData.EMPTY);
    }

    /**
     * 便捷方法：写入漏斗过滤链（EMPTY 时移除组件，节省 NBT）。
     */
    public static void setHopperFilter(ItemStack stack, com.qiqi.li.living.transfer.FilterData filter) {
        if (filter == null || filter.equals(com.qiqi.li.living.transfer.FilterData.EMPTY)) {
            stack.remove(LIVING_HOPPER_FILTER.value());
        } else {
            stack.set(LIVING_HOPPER_FILTER.value(), filter);
        }
    }

    /**
     * 便捷方法：获取TNT数据。
     */
    public static LivingTntData getTntData(ItemStack stack) {
        return getData(stack, LIVING_TNT_DATA.value(), LivingTntData.DEFAULT);
    }

    /**
     * 便捷方法：设置TNT数据。
     */
    public static void setTntData(ItemStack stack, LivingTntData data) {
        setData(stack, LIVING_TNT_DATA.value(), data, LivingTntData.DEFAULT);
    }

    /**
     * 便捷方法：获取水桶数据。
     */
    public static LivingWaterBucketData getWaterBucketData(ItemStack stack) {
        return getData(stack, LIVING_WATER_BUCKET_DATA.value(), LivingWaterBucketData.EMPTY);
    }

    /**
     * 便捷方法：设置水桶数据。
     */
    public static void setWaterBucketData(ItemStack stack, LivingWaterBucketData data) {
        setData(stack, LIVING_WATER_BUCKET_DATA.value(), data, LivingWaterBucketData.EMPTY);
    }

    /**
     * 便捷方法：获取末影箱数据。
     */
    public static LivingEnderChestData getEnderChestData(ItemStack stack) {
        return getData(stack, LIVING_ENDER_CHEST_DATA.value(), LivingEnderChestData.EMPTY);
    }

    /**
     * 便捷方法：设置末影箱数据。
     */
    public static void setEnderChestData(ItemStack stack, LivingEnderChestData data) {
        setData(stack, LIVING_ENDER_CHEST_DATA.value(), data, LivingEnderChestData.EMPTY);
    }

    public static LivingWaterWheelData getWaterWheelData(ItemStack stack) {
        return getData(stack, LIVING_WATER_WHEEL_DATA.value(), LivingWaterWheelData.EMPTY);
    }

    public static void setWaterWheelData(ItemStack stack, LivingWaterWheelData data) {
        setData(stack, LIVING_WATER_WHEEL_DATA.value(), data, LivingWaterWheelData.EMPTY);
    }

    public static LivingRedstoneData getRedstoneData(ItemStack stack) {
        return getData(stack, LIVING_REDSTONE_DATA.value(), LivingRedstoneData.DEFAULT);
    }

    public static void setRedstoneData(ItemStack stack, LivingRedstoneData data) {
        setData(stack, LIVING_REDSTONE_DATA.value(), data, LivingRedstoneData.DEFAULT);
    }

    public static LivingRedstoneTorchData getRedstoneTorchData(ItemStack stack) {
        return getData(stack, LIVING_REDSTONE_TORCH_DATA.value(), LivingRedstoneTorchData.DEFAULT);
    }

    public static void setRedstoneTorchData(ItemStack stack, LivingRedstoneTorchData data) {
        setData(stack, LIVING_REDSTONE_TORCH_DATA.value(), data, LivingRedstoneTorchData.DEFAULT);
    }

    public static LivingButtonData getButtonData(ItemStack stack) {
        return getData(stack, LIVING_BUTTON_DATA.value(), LivingButtonData.DEFAULT);
    }

    public static void setButtonData(ItemStack stack, LivingButtonData data) {
        setData(stack, LIVING_BUTTON_DATA.value(), data, LivingButtonData.DEFAULT);
    }

    public static LivingLeverData getLeverData(ItemStack stack) {
        return getData(stack, LIVING_LEVER_DATA.value(), LivingLeverData.DEFAULT);
    }

    public static void setLeverData(ItemStack stack, LivingLeverData data) {
        setData(stack, LIVING_LEVER_DATA.value(), data, LivingLeverData.DEFAULT);
    }

    public static FarmlandPlantComponent getFarmlandPlant(ItemStack stack) {
        return getData(stack, FARMLAND_PLANT.value(), FarmlandPlantComponent.DEFAULT);
    }

    public static void setFarmlandPlant(ItemStack stack, FarmlandPlantComponent data) {
        setData(stack, FARMLAND_PLANT.value(), data, FarmlandPlantComponent.DEFAULT);
    }

    /** 便捷方法：获取活工具记忆（挖掘记忆 + 交互记忆）。 */
    public static LivingToolMemory getToolMemory(ItemStack stack) {
        return getData(stack, LIVING_TOOL_MEMORY.value(), LivingToolMemory.DEFAULT);
    }

    /** 便捷方法：设置活工具记忆（等于 DEFAULT 即无记忆时自动移除组件）。 */
    public static void setToolMemory(ItemStack stack, LivingToolMemory memory) {
        setData(stack, LIVING_TOOL_MEMORY.value(), memory, LivingToolMemory.DEFAULT);
    }

    /** 便捷方法：获取活工具挖掘进度；<b>null = 当前没在挖</b>。 */
    @Nullable
    public static LivingToolProgress getToolProgress(ItemStack stack) {
        return stack.get(LIVING_TOOL_PROGRESS.value());
    }

    /** 便捷方法：写入活工具挖掘进度（null 表示清除，即停止挖掘）。 */
    public static void setToolProgress(ItemStack stack, @Nullable LivingToolProgress progress) {
        if (progress == null) {
            stack.remove(LIVING_TOOL_PROGRESS.value());
        } else {
            stack.set(LIVING_TOOL_PROGRESS.value(), progress);
        }
    }

    /**
     * 便捷方法：获取活工具主人 UUID。
     *
     * @return 主人 UUID；<b>null 表示无主人</b>（未记录 / 自动活化），
     *         调用方应回退到通用 FakePlayer
     */
    @Nullable
    public static UUID getToolOwner(ItemStack stack) {
        return stack.get(LIVING_TOOL_OWNER.value());
    }

    /** 便捷方法：写入活工具主人 UUID（null 表示清除）。 */
    public static void setToolOwner(ItemStack stack, @Nullable UUID owner) {
        if (owner == null) {
            stack.remove(LIVING_TOOL_OWNER.value());
        } else {
            stack.set(LIVING_TOOL_OWNER.value(), owner);
        }
    }

    /** 活耕地湿润标志（未打标志 = 干燥；图标 moist/dry 变体切换数据源） */
    public static boolean isFarmlandMoist(ItemStack stack) {
        return getData(stack, LIVING_FARMLAND_MOIST.value(), Boolean.FALSE);
    }

    public static void setFarmlandMoist(ItemStack stack, boolean moist) {
        setData(stack, LIVING_FARMLAND_MOIST.value(), moist, Boolean.FALSE);
    }

    public static LivingRedstoneLampData getLampData(ItemStack stack) {
        return getData(stack, LIVING_REDSTONE_LAMP_DATA.value(), LivingRedstoneLampData.DEFAULT);
    }

    public static void setLampData(ItemStack stack, LivingRedstoneLampData data) {
        setData(stack, LIVING_REDSTONE_LAMP_DATA.value(), data, LivingRedstoneLampData.DEFAULT);
    }

    public static LivingRepeaterData getRepeaterData(ItemStack stack) {
        return getData(stack, LIVING_REPEATER_DATA.value(), LivingRepeaterData.DEFAULT);
    }

    public static void setRepeaterData(ItemStack stack, LivingRepeaterData data) {
        setData(stack, LIVING_REPEATER_DATA.value(), data, LivingRepeaterData.DEFAULT);
    }

    public static LivingComparatorData getComparatorData(ItemStack stack) {
        return getData(stack, LIVING_COMPARATOR_DATA.value(), LivingComparatorData.DEFAULT);
    }

    public static void setComparatorData(ItemStack stack, LivingComparatorData data) {
        setData(stack, LIVING_COMPARATOR_DATA.value(), data, LivingComparatorData.DEFAULT);
    }

    public static LivingCutCopperData getCutCopperData(ItemStack stack) {
        return getData(stack, LIVING_CUT_COPPER_DATA.value(), LivingCutCopperData.DEFAULT);
    }

    public static void setCutCopperData(ItemStack stack, LivingCutCopperData data) {
        setData(stack, LIVING_CUT_COPPER_DATA.value(), data, LivingCutCopperData.DEFAULT);
    }

    public static LivingGrateData getGrateData(ItemStack stack) {
        return getData(stack, LIVING_GRATE_DATA.value(), LivingGrateData.DEFAULT);
    }

    public static com.qiqi.li.living.domain.power.LivingWaxedCutData getWaxedCutData(ItemStack stack) {
        return getData(stack, LIVING_WAXED_CUT_DATA.value(),
                com.qiqi.li.living.domain.power.LivingWaxedCutData.DEFAULT);
    }

    public static void setWaxedCutData(ItemStack stack,
                                       com.qiqi.li.living.domain.power.LivingWaxedCutData data) {
        setData(stack, LIVING_WAXED_CUT_DATA.value(), data,
                com.qiqi.li.living.domain.power.LivingWaxedCutData.DEFAULT);
    }

    public static com.qiqi.li.living.domain.power.LivingWaxedChiseledData getWaxedChiseledData(ItemStack stack) {
        return getData(stack, LIVING_WAXED_CHISELED_DATA.value(),
                com.qiqi.li.living.domain.power.LivingWaxedChiseledData.DEFAULT);
    }

    public static void setWaxedChiseledData(ItemStack stack,
                                            com.qiqi.li.living.domain.power.LivingWaxedChiseledData data) {
        setData(stack, LIVING_WAXED_CHISELED_DATA.value(), data,
                com.qiqi.li.living.domain.power.LivingWaxedChiseledData.DEFAULT);
    }

    public static com.qiqi.li.living.domain.power.LivingWaxedGeneratorData getGeneratorData(ItemStack stack) {
        return getData(stack, LIVING_GENERATOR_DATA.value(),
                com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DEFAULT);
    }

    public static void setGeneratorData(ItemStack stack,
                                        com.qiqi.li.living.domain.power.LivingWaxedGeneratorData data) {
        setData(stack, LIVING_GENERATOR_DATA.value(), data,
                com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DEFAULT);
    }

    public static com.qiqi.li.living.domain.power.LivingWaxedBulbData getWaxedBulbData(ItemStack stack) {
        return getData(stack, LIVING_WAXED_BULB_DATA.value(),
                com.qiqi.li.living.domain.power.LivingWaxedBulbData.DEFAULT);
    }

    public static void setWaxedBulbData(ItemStack stack,
                                        com.qiqi.li.living.domain.power.LivingWaxedBulbData data) {
        setData(stack, LIVING_WAXED_BULB_DATA.value(), data,
                com.qiqi.li.living.domain.power.LivingWaxedBulbData.DEFAULT);
    }

    public static void setGrateData(ItemStack stack, LivingGrateData data) {
        setData(stack, LIVING_GRATE_DATA.value(), data, LivingGrateData.DEFAULT);
    }

    public static LivingCopperBulbData getCopperBulbData(ItemStack stack) {
        return getData(stack, LIVING_COPPER_BULB_DATA.value(), LivingCopperBulbData.DEFAULT);
    }

    public static void setCopperBulbData(ItemStack stack, LivingCopperBulbData data) {
        setData(stack, LIVING_COPPER_BULB_DATA.value(), data, LivingCopperBulbData.DEFAULT);
    }

    public static LivingCopperSignalData getCopperSignal(ItemStack stack) {
        return getData(stack, LIVING_COPPER_SIGNAL.value(), LivingCopperSignalData.DEFAULT);
    }

    public static void setCopperSignal(ItemStack stack, LivingCopperSignalData data) {
        setData(stack, LIVING_COPPER_SIGNAL.value(), data, LivingCopperSignalData.DEFAULT);
    }
}