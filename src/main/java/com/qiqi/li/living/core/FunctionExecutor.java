package com.qiqi.li.living.core;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.components.*;

/**
 * 功能执行器 —— 活物品功能的通用工具类。
 *
 * 提供组件状态管理、槽位解析、组件实例解析等通用能力，
 * 供各 LivingItemFunction 实现类在自己的 tick() 中调用。
 *
 * 设计原则：
 *   FunctionExecutor 不包含任何业务逻辑（如 canProgress 判断、pauseTick 分支、
 *   handleCompletion 等），这些由各 LivingItemFunction 自行编排。
 *   这样新增活物品类型时，只需实现自己的 tick() 编排逻辑，无需修改此类。
 *
 * 提供的工具方法：
 *   - loadOrCreateStates()：从物品 NBT 加载或创建组件状态
 *   - saveStatesToStack()：将组件状态保存回物品 NBT
 *   - buildContext()：解析槽位并创建 ComponentContext
 *   - resolveComponent()：解析组件条目为实际实例
 *   - findComponent()：从配置中查找指定类型的组件
 *   - getComponent()：获取或创建组件实例（带缓存）
 */
public final class FunctionExecutor {

    /** 单例实例 */
    public static final FunctionExecutor INSTANCE = new FunctionExecutor();

    /** 组件实例缓存（按类型存储，避免每次 tick 都反射创建） */
    private final Map<Class<? extends ILivingComponent>, ILivingComponent> componentCache = new HashMap<>();

    private FunctionExecutor() {}

    /**
     * 从物品 NBT 加载或创建组件状态。
     *
     * 对于 NBT 中已存在的状态，从对应的子标签反序列化；
     * 对于新组件，调用 createDefaultState() 创建初始状态。
     *
     * @param stack 活物品 ItemStack
     * @param config 功能配置
     * @return 组件 ID → 组件状态的映射
     */
    public Map<String, ComponentState> loadOrCreateStates(ItemStack stack, LivingFunctionConfig config) {
        Map<String, ComponentState> states = new HashMap<>();

        CompoundTag functionTag = LivingItemManager.getFunctionData(stack, config.getFunctionId());

        for (var entry : config.getComponents()) {
            ILivingComponent component = resolveComponent(config, entry);
            String compId = component.getComponentId();

            if (functionTag.contains(compId)) {
                states.put(compId, ComponentState.fromNBT(functionTag.getCompound(compId)));
            } else {
                states.put(compId, component.createDefaultState());
            }
        }

        return states;
    }

    /**
     * 将组件状态保存回物品 NBT。
     *
     * @param stack 活物品 ItemStack
     * @param config 功能配置
     * @param states 组件 ID → 组件状态的映射
     */
    public void saveStatesToStack(ItemStack stack, LivingFunctionConfig config,
                                   Map<String, ComponentState> states) {
        CompoundTag functionTag = new CompoundTag();

        for (var entry : states.entrySet()) {
            functionTag.put(entry.getKey(), entry.getValue().toNBT());
        }

        LivingItemManager.setFunctionData(stack, config.getFunctionId(), functionTag);
    }

    /**
     * 解析槽位并创建组件执行上下文。
     *
     * 通用流程：
     * 1. 通过 DirectionModeComponent.resolveSlots() 解析槽位索引
     *    （SLOTS 模式：input/fuel/output，TRANSFER 模式：source/target）
     * 2. 创建 ComponentContext（包含容器、解析结果、世界、状态）
     *
     * @param config 功能配置
     * @param states 组件状态映射
     * @param containerCtx 容器上下文
     * @param slot 活物品所在槽位
     * @param level 当前世界
     * @return 组件执行上下文
     */
    public ComponentContext buildContext(LivingFunctionConfig config,
                                         Map<String, ComponentState> states,
                                         ContainerContext containerCtx,
                                         int slot, Level level) {
        int containerSize = containerCtx.getSize();
        int containerWidth = containerCtx.getWidth();

        DirectionModeComponent.ResolvedSlots resolvedSlots;

        DirectionModeComponent dirComp = findComponent(config, DirectionModeComponent.class);
        if (dirComp != null) {
            ComponentState dirState = states.get(DirectionModeComponent.ID);
            resolvedSlots = dirComp.resolveSlots(dirState, slot, containerSize, containerWidth);
        } else {
            resolvedSlots = DirectionModeComponent.ResolvedSlots.empty();
        }

        return new ComponentContext(containerCtx, resolvedSlots, level, states);
    }

    /**
     * 获取或创建组件实例（带缓存）。
     *
     * @param clazz 组件类
     * @return 组件实例
     */
    public ILivingComponent getComponent(Class<? extends ILivingComponent> clazz) {
        return componentCache.computeIfAbsent(clazz, c -> {
            try {
                return c.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate component: " + c.getName(), e);
            }
        });
    }

    /**
     * 解析组件条目为实际组件实例。
     * 优先使用预配置实例，其次查找配置中的实例，最后通过反射创建。
     */
    public ILivingComponent resolveComponent(LivingFunctionConfig config, LivingFunctionConfig.ComponentEntry entry) {
        if (entry.preconfiguredInstance() != null) {
            return entry.preconfiguredInstance();
        }

        ILivingComponent configured = config.getConfiguredInstance(entry.componentClass());
        if (configured != null) {
            return configured;
        }

        return getComponent(entry.componentClass());
    }

    /**
     * 从配置中查找指定类型的组件实例。
     */
    @SuppressWarnings("unchecked")
    public <T extends ILivingComponent> T findComponent(LivingFunctionConfig config, Class<T> type) {
        for (var entry : config.getComponents()) {
            ILivingComponent comp = resolveComponent(config, entry);
            if (type.isInstance(comp)) {
                return (T) comp;
            }
        }
        return null;
    }
}