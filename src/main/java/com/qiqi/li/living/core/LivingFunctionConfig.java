package com.qiqi.li.living.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import com.qiqi.li.living.core.components.ILivingComponent;
import com.qiqi.li.living.core.interaction.InteractionEntry;
import com.qiqi.li.living.core.interaction.InteractionRegistry;
import com.qiqi.li.living.core.orchestrator.LivingOrchestrator;
import com.qiqi.li.living.core.orchestrator.Orchestrators;

/**
 * 活物品功能配置 —— 声明式定义活物品的组件组合和参数。
 *
 * 每种活物品类型（活熔炉、活漏斗等）通过此配置声明其需要的组件和参数。
 * FunctionExecutor 在 tick 时根据配置加载组件、创建状态、编排执行。
 *
 * 配置内容：
 *   - 组件列表：声明需要哪些组件以及每个组件的配置参数
 *   - 功能 ID：用于 NBT 数据隔离（不同功能的 NBT 存储在不同键下）
 *   - 堆叠加速开关：是否允许堆叠物品加速处理
 *
 * 组件注册方式：
 *   1. 按类注册：addComponent(Class, ComponentConfig)
 *      FunctionExecutor 通过反射创建实例，适用于无状态或简单组件
 *
 *   2. 按实例注册：addComponent(ILivingComponent)
 *      直接使用预配置的实例，适用于需要构造参数的组件（如 DirectionModeComponent）
 *      预配置实例会被缓存，避免每次 tick 都重新创建
 *
 * 使用示例：
 * <pre>
 * // 活熔炉配置
 * private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
 *     .withFunctionId("living_furnace")
 *     .withStackMultiplier(true)
 *     .addComponent(new DirectionModeComponent(Map.of(
 *         "input", Pos2D.LEFT, "fuel", Pos2D.DOWN, "output", Pos2D.RIGHT)))
 *     .addComponent(FuelConsumeComponent.class,
 *         ComponentConfig.of("recipe_type", RecipeType.SMELTING))
 *     .addComponent(ProgressComponent.class,
 *         ComponentConfig.of("total_ticks", 200))
 *     .addComponent(ItemTransformComponent.class,
 *         ComponentConfig.of("recipe_type", RecipeType.SMELTING));
 * </pre>
 */
public class LivingFunctionConfig {

    /** 组件注册列表（按注册顺序执行） */
    private final List<ComponentEntry> components = new ArrayList<>();

    /** 预配置的组件实例缓存（按类型查找） */
    private final Map<Class<? extends ILivingComponent>, ILivingComponent> configuredInstances = new HashMap<>();

    /** 是否启用堆叠加速（多个活物品堆叠时加速处理） */
    private boolean enableStackMultiplier = true;

    /** 功能 ID，用于 NBT 数据隔离 */
    private String functionId = "unknown";

    /** 编排器（定义组件的执行编排策略） */
    private LivingOrchestrator orchestrator = Orchestrators.SIMPLE;

    /**
     * 组件注册条目。
     *
     * @param componentClass 组件类（用于反射创建实例）
     * @param config 组件配置参数
     * @param preconfiguredInstance 预配置的组件实例（可为 null）
     */
    public record ComponentEntry(
        Class<? extends ILivingComponent> componentClass,
        ComponentConfig config,
        @Nullable ILivingComponent preconfiguredInstance
    ) {}

    /**
     * 按类注册组件（FunctionExecutor 通过反射创建实例）。
     *
     * @param clazz 组件类
     * @param config 组件配置参数
     * @return this（支持链式调用）
     */
    public LivingFunctionConfig addComponent(Class<? extends ILivingComponent> clazz, ComponentConfig config) {
        components.add(new ComponentEntry(clazz, config, null));
        return this;
    }

    /**
     * 按实例注册组件（使用预配置的实例，避免反射创建）。
     *
     * 适用于需要构造参数的组件（如 DirectionModeComponent）。
     * 实例会被缓存到 configuredInstances 中，供 FunctionExecutor 查找。
     *
     * @param instance 预配置的组件实例
     * @return this（支持链式调用）
     */
    public LivingFunctionConfig addComponent(ILivingComponent instance) {
        components.add(new ComponentEntry(instance.getClass(), ComponentConfig.empty(), instance));
        configuredInstances.put(instance.getClass(), instance);
        return this;
    }

    /**
     * 获取预配置的组件实例。
     *
     * @param clazz 组件类
     * @return 预配置的实例，如果不存在返回 null
     */
    @Nullable
    public ILivingComponent getConfiguredInstance(Class<? extends ILivingComponent> clazz) {
        return configuredInstances.get(clazz);
    }

    /**
     * 设置是否启用堆叠加速。
     *
     * @param enabled 是否启用
     * @return this（支持链式调用）
     */
    public LivingFunctionConfig withStackMultiplier(boolean enabled) {
        this.enableStackMultiplier = enabled;
        return this;
    }

    /**
     * 设置功能 ID。
     *
     * @param id 功能 ID（如 "living_furnace"、"living_hopper"）
     * @return this（支持链式调用）
     */
    public LivingFunctionConfig withFunctionId(String id) {
        this.functionId = id;
        return this;
    }

    /**
     * 设置编排器。
     *
     * @param orchestrator 编排器实例（如 Orchestrators.SIMPLE、Orchestrators.FUEL_PROGRESS）
     * @return this（支持链式调用）
     */
    public LivingFunctionConfig withOrchestrator(LivingOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
        return this;
    }

    /**
     * 注册GUI交互规则。
     *
     * 声明此活物品可被哪种物品、通过哪个按键触发什么动作。
     * 交互规则会被自动注册到 InteractionRegistry 中。
     *
     * 使用示例：
     * <pre>
     * // 活TNT声明"可被活打火石右键点燃"
     * .addInteraction(new InteractionEntry(
     *     Items.TNT,               // 目标物品（我是谁）
     *     Items.FLINT_AND_STEEL,   // 触发物品（谁来交互）
     *     1,                       // 鼠标按键（右键）
     *     "ignite"                 // 动作ID
     * ))
     * </pre>
     *
     * @param entry 交互规则条目
     * @return this（支持链式调用）
     */
    public LivingFunctionConfig addInteraction(InteractionEntry entry) {
        InteractionRegistry.register(entry);
        return this;
    }

    public List<ComponentEntry> getComponents() { return components; }
    public boolean isStackMultiplierEnabled() { return enableStackMultiplier; }
    public String getFunctionId() { return functionId; }
    public LivingOrchestrator getOrchestrator() { return orchestrator; }
}