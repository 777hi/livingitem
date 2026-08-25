package com.qiqi.li.living.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.qiqi.li.living.domain.redstone.LivingCopperBulbData;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponentType;
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

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingHopperData>> LIVING_HOPPER_DATA =
            DATA_COMPONENT_TYPES.register("living_hopper_data", () ->
                    DataComponentType.<LivingHopperData>builder()
                            .persistent(LivingHopperData.CODEC)
                            .networkSynchronized(LivingHopperData.STREAM_CODEC)
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
        stack.remove(LIVING_HOPPER_DATA.value());
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
    }

    public static void setLiving(ItemStack stack, boolean living) {
        if (living) {
            stack.set(IS_LIVING.value(), true);
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

    public static void setGrateData(ItemStack stack, LivingGrateData data) {
        setData(stack, LIVING_GRATE_DATA.value(), data, LivingGrateData.DEFAULT);
    }

    public static LivingCopperBulbData getCopperBulbData(ItemStack stack) {
        return getData(stack, LIVING_COPPER_BULB_DATA.value(), LivingCopperBulbData.DEFAULT);
    }

    public static void setCopperBulbData(ItemStack stack, LivingCopperBulbData data) {
        setData(stack, LIVING_COPPER_BULB_DATA.value(), data, LivingCopperBulbData.DEFAULT);
    }
}