package com.qiqi.li.living;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 活物品管理器。
 * 负责：
 * 1. 注册自定义 DataComponent 类型（IS_LIVING 和 LIVING_FUNCTION_DATA）
 * 2. 管理已注册的活物品功能（LivingItemFunction 列表）
 * 3. 提供辅助方法：获取/设置活物品数据、判断是否为活物品
 *
 * DataComponent 是 Minecraft 1.20.5+ 引入的数据组件系统，替代了旧的 NBT 自定义标签。
 * 相比直接读写 NBT，DataComponent 有以下优势：
 * - 类型安全：通过泛型明确数据类型，避免运行时类型错误
 * - 自动持久化与同步：通过 Codec 和 StreamCodec 自动处理 NBT 和网络传输
 * - 与原版系统深度集成：可参与配方匹配、附魔逻辑等原版机制
 */
public class LivingItemManager {
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 自定义 DataComponentType 的注册器。
     * 所有自定义数据组件类型都需要通过它注册到 Minecraft 的注册表中。
     */
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, com.qiqi.li.LivingItem.MOD_ID);

    /**
     * IS_LIVING 组件 —— 标记物品是否为活物品。
     * 存储一个简单的布尔值。当 ItemStack 中包含此组件且值为 true 时，
     * 视为活物品，将被事件系统扫描并执行活物品功能。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> IS_LIVING =
            DATA_COMPONENT_TYPES.register("is_living", () ->
                    DataComponentType.<Boolean>builder()
                            // 持久化到 NBT：使用 Codec.BOOL 对布尔值进行编解码
                            .persistent(Codec.BOOL)
                            // 网络同步：使用 ByteBufCodecs.BOOL 进行网络传输
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    /**
     * LIVING_FUNCTION_DATA 组件 —— 存储所有活物品功能的运行时数据。
     * 类型从原来的 CompoundTag 改为自定义的 {@link LivingFunctionData}。
     *
     * 关键改动：
     * - 实现了 {@link TooltipProvider} 接口，可以直接向物品 tooltip 添加信息
     * - 通过 Codec/StreamCodec 统一处理持久化和网络同步
     * - 使用不可变设计（setFunctionData 返回新实例），符合 DataComponent 系统的要求
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LivingFunctionData>> LIVING_FUNCTION_DATA =
            DATA_COMPONENT_TYPES.register("living_function_data", () ->
                    DataComponentType.<LivingFunctionData>builder()
                            // 持久化：使用 LivingFunctionData.CODEC 序列化到 NBT
                            .persistent(LivingFunctionData.CODEC)
                            // 网络同步：使用 LivingFunctionData.STREAM_CODEC 在网络间传输
                            .networkSynchronized(LivingFunctionData.STREAM_CODEC)
                            .build());

    /** 已注册的活物品功能列表 —— 所有活物品功能都需要通过 registerFunction 注册到此 */
    private static final List<LivingItemFunction> FUNCTIONS = new ArrayList<>();

    /** 已注册功能的不可变视图 —— 提供给外部只读访问 */
    private static final List<LivingItemFunction> FUNCTIONS_VIEW = Collections.unmodifiableList(FUNCTIONS);

    /** 适用功能缓存：Item → 适用的功能列表，避免每次 tick 都遍历 FUNCTIONS 并调用 canApply */
    private static final Map<Item, List<LivingItemFunction>> APPLICABLE_CACHE = new HashMap<>();

    /**
     * 注册一个活物品功能。
     * 应在 mod 初始化阶段调用（如 LivingItem 类的构造函数或主类的 @Mod 方法）。
     *
     * @param function 要注册的活物品功能实例
     */
    public static void registerFunction(LivingItemFunction function) {
        LOGGER.info("Registering living item function: {}", function.getFunctionId());
        FUNCTIONS.add(function);
    }

    /**
     * 获取所有已注册的活物品功能。
     * 供 {@link LivingFunctionData#addToTooltip} 遍历时使用。
     *
     * @return 不可变的已注册功能列表
     */
    public static List<LivingItemFunction> getAllFunctions() {
        return FUNCTIONS_VIEW;
    }

    /**
     * 判断一个物品是否为活物品。
     * 检查是否包含 IS_LIVING 组件。
     *
     * @param stack 要检查的物品
     * @return 如果是活物品则返回 true
     */
    public static boolean isLivingItem(ItemStack stack) {
        return !stack.isEmpty() && stack.has(IS_LIVING.value());
    }

    /**
     * 获取当前物品上适用的活物品功能。
     * 首先判断是否为活物品，然后依次调用每个 function.canApply(stack) 进行筛选。
     *
     * @param stack 要查询的物品
     * @return 适用于该物品的功能列表（新创建的列表，可安全遍历）
     */
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

    /**
     * 获取指定活物品功能的数据。
     * 从 LIVING_FUNCTION_DATA 组件中读取对应 functionId 子 tag。
     *
     * @param stack 物品
     * @param functionId 功能 ID
     * @return 该功能的数据 tag；如果不存在或为空则返回新的空 tag
     */
    public static CompoundTag getFunctionData(ItemStack stack, String functionId) {
        LivingFunctionData allData = stack.get(LIVING_FUNCTION_DATA.value());
        if (allData != null && !allData.isEmpty()) {
            return allData.getFunctionData(functionId);
        }
        return new CompoundTag();
    }

    /**
     * 设置指定活物品功能的数据。
     *
     * 注意：由于 {@link LivingFunctionData} 遵循不可变设计（DataComponent 系统的要求），
     * 每次修改数据都会创建新的 LivingFunctionData 实例。
     *
     * @param stack 要修改的物品
     * @param functionId 功能 ID
     * @param data 新的功能数据；为空时将移除该功能条目
     */
    public static void setFunctionData(ItemStack stack, String functionId, CompoundTag data) {
        // 从物品上读取当前的 LivingFunctionData，若不存在则使用 EMPTY
        LivingFunctionData oldData = stack.get(LIVING_FUNCTION_DATA.value());
        if (oldData == null) {
            oldData = LivingFunctionData.EMPTY;
        }
        // 创建新的 LivingFunctionData（不可变设计 —— setFunctionData 返回新实例）
        LivingFunctionData newData = oldData.setFunctionData(functionId, data);
        if (newData.isEmpty()) {
            // 如果所有功能数据都被清空，从物品上移除此组件以节省空间
            stack.remove(LIVING_FUNCTION_DATA.value());
        } else {
            // 将新组件设置到物品上
            stack.set(LIVING_FUNCTION_DATA.value(), newData);
        }
    }

    /**
     * 清除物品上所有活物品相关的数据。
     * 包括 IS_LIVING 和 LIVING_FUNCTION_DATA 两个组件。
     *
     * @param stack 要清除数据的物品
     */
    public static void clearLivingData(ItemStack stack) {
        stack.remove(IS_LIVING.value());
        stack.remove(LIVING_FUNCTION_DATA.value());
    }

    /**
     * 将物品标记为活物品。
     * 通过设置 IS_LIVING 组件为 true 来实现。
     *
     * @param stack 要标记的物品
     * @param living 是否为活物品（true 设置标记，false 清除所有数据）
     */
    public static void setLiving(ItemStack stack, boolean living) {
        if (living) {
            stack.set(IS_LIVING.value(), true);
        } else {
            clearLivingData(stack);
        }
    }

    /**
     * 根据容器生成一个稳定的唯一标识符。
     * 用于在区块重新加载或容器对象重建时，仍然可以正确关联到原有的活物品状态。
     *
     * 稳定 key 的策略：
     * - 玩家背包：player_ + UUID
     * - 方块实体（如箱子）：blockentity_ + 维度ID + 坐标 (x_y_z)
     * - 其他容器：container_ + 对象 hashcode（不稳定，但用于临时容器）
     *
     * @param container 容器
     * @return 稳定的 key 字符串
     */
    public static String getContainerStableKey(Container container) {
        if (container instanceof Inventory inv) {
            // 玩家背包：使用玩家 UUID，确保跨区块加载仍然是同一个容器
            return "player_" + inv.player.getStringUUID();
        }
        if (container instanceof BlockEntity be) {
            // 方块实体（如 ChestBlockEntity）：使用维度 ID 和方块坐标
            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            String dimKey = level != null ? level.dimension().location().toString() : "unknown";
            return "blockentity_" + dimKey + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
        // 其他容器（如 DoubleContainer、DispenseBlockContainer 等）使用 hashcode 作为回退方案
        return "container_" + Integer.toHexString(container.hashCode());
    }
}