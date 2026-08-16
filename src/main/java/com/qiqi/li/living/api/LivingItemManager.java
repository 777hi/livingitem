package com.qiqi.li.living.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();
    private static final List<LivingItemFunction> FUNCTIONS_VIEW = Collections.unmodifiableList(FUNCTIONS);
    private static final Map<Item, List<LivingItemFunction>> APPLICABLE_CACHE = new HashMap<>();

    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering living item function: {}", function.getFunctionId());
        FUNCTIONS.add(function);
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

    public static void clearLivingData(ItemStack stack) {
        stack.remove(IS_LIVING.value());
        stack.remove(LIVING_TNT_DATA.value());
        stack.remove(LIVING_WATER_BUCKET_DATA.value());
        stack.remove(LIVING_WATER_WHEEL_DATA.value());
        stack.remove(LIVING_FURNACE_DATA.value());
        stack.remove(LIVING_HOPPER_DATA.value());
        stack.remove(LIVING_ENDER_CHEST_DATA.value());
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
}