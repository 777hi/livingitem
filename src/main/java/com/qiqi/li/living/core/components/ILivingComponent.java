package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

/**
 * 活物品组件接口 —— 定义组件的基本生命周期。
 *
 * 组件是活物品功能的最小可复用单元。每种活物品通过 LivingFunctionConfig
 * 声明式组合所需的组件，由 FunctionExecutor 编排执行。
 *
 * 生命周期：
 * 1. createDefaultState() — 创建初始状态（首次 tick 时调用）
 * 2. tick() — 每 tick 执行一次核心逻辑
 * 3. appendTooltip() — 追加 Tooltip 显示信息
 *
 * 组件间协作：
 *   组件通过 ComponentContext.getComponentState() 读取其他组件的状态，
 *   实现跨组件数据访问。例如 ItemTransferComponent 读取 DirectionModeComponent
 *   的状态获取传输方向。
 *
 * 实现规范：
 *   - 组件应该是无状态的（状态存储在 ComponentState 中）
 *   - 组件 ID 必须全局唯一
 *   - tick() 方法应该幂等（重复调用不产生副作用）
 */
public interface ILivingComponent {

    /**
     * 获取组件的唯一标识符。
     *
     * 用于在 ComponentState 和 NBT 中标识此组件的数据。
     * 必须全局唯一，不能与其他组件重复。
     *
     * @return 组件 ID（如 "progress"、"fuel"、"direction_mode"）
     */
    String getComponentId();

    /**
     * 每 tick 执行一次的核心逻辑。
     *
     * 由 FunctionExecutor 在活物品 tick 时调用。
     * 组件通过 state 参数读写运行时数据，
     * 通过 ctx 参数访问容器和世界信息。
     *
     * @param context 组件执行上下文（容器、槽位、世界、状态）
     * @param hostSlot 活物品所在的槽位索引
     * @param hostStack 活物品的 ItemStack
     * @param state 本组件的运行时状态
     * @param config 本组件的配置参数
     */
    void tick(ComponentContext context, int hostSlot, ItemStack hostStack,
              ComponentState state, ComponentConfig config);

    /**
     * 创建组件的默认状态。
     *
     * 在活物品首次 tick 时调用，用于初始化组件的运行时数据。
     *
     * @return 包含默认值的组件状态
     */
    ComponentState createDefaultState();

    /**
     * 创建组件的活化初始状态。
     *
     * 在活物品被活化（setLiving）时调用，用于初始化需要在活化时就存在的数据。
     * 与 createDefaultState() 的区别：此方法可以访问 ComponentConfig，
     * 因此可以根据配置参数初始化状态（如 ProgressComponent 根据 total_ticks 设置 total）。
     *
     * 默认实现直接返回 createDefaultState()，无需特殊初始化的组件无需覆写。
     *
     * @param config 本组件的配置参数
     * @return 包含初始值的组件状态
     */
    default ComponentState createInitialState(ComponentConfig config) {
        return createDefaultState();
    }

    /**
     * 追加 Tooltip 信息。
     *
     * 默认空实现，组件可覆盖此方法以显示运行时信息。
     *
     * @param state 本组件的运行时状态
     * @param tooltipAdder Tooltip 追加器
     */
    default void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {}
}