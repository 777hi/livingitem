package com.qiqi.li.living.core.orchestrator;

import java.util.Map;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.ILivingComponent;

/**
 * 活物品编排器接口 —— 定义组件的执行编排策略。
 *
 * 不同的活物品有不同的组件协作流程：
 * - 活漏斗：直接遍历组件 tick，无额外判断
 * - 活磨石：检查输入有效性 → tick/pauseTick → 完成时转化
 * - 活熔炉：检查燃料+输入 → tick/pauseTick → 完成时转化
 *
 * 编排器将"如何协调组件执行"从 LivingItemFunction 中分离出来，
 * 使新活物品只需选择合适的编排器，无需重写编排逻辑。
 *
 * 使用方式：
 * <pre>
 * // 在 LivingFunctionConfig 中指定编排器
 * new LivingFunctionConfig()
 *     .withOrchestrator(Orchestrators.SIMPLE)   // 或 PROGRESS / FUEL_PROGRESS
 *     .addComponent(...)
 * </pre>
 *
 * 内置编排器：
 * - {@link Orchestrators#SIMPLE}：直接遍历组件 tick
 * - {@link Orchestrators#PROGRESS}：检查输入 → tick/pauseTick → 完成时转化
 * - {@link Orchestrators#FUEL_PROGRESS}：检查燃料+输入 → tick/pauseTick → 完成时转化
 */
public interface LivingOrchestrator {

    /**
     * 编排单个活物品的组件执行。
     *
     * @param ctx 组件执行上下文（包含容器、解析槽位、世界、状态）
     * @param slot 活物品所在槽位
     * @param stack 活物品 ItemStack
     * @param states 组件 ID → 组件状态的映射
     * @param config 功能配置（包含组件列表和编排器）
     * @param fe FunctionExecutor 工具实例
     */
    void orchestrate(ComponentContext ctx, int slot, ItemStack stack,
                     Map<String, ComponentState> states,
                     LivingFunctionConfig config, FunctionExecutor fe);

    /**
     * 通用辅助：遍历所有组件执行正常 tick。
     */
    static void tickAllComponents(ComponentContext ctx, int slot, ItemStack stack,
                                  Map<String, ComponentState> states,
                                  LivingFunctionConfig config, FunctionExecutor fe) {
        for (var entry : config.getComponents()) {
            ILivingComponent component = fe.resolveComponent(config, entry);
            ComponentState state = states.get(component.getComponentId());
            component.tick(ctx, slot, stack, state, entry.config());
        }
    }

    /**
     * 通用辅助：从配置中查找指定类型组件的 ComponentConfig。
     */
    static ComponentConfig findConfig(Class<? extends ILivingComponent> type, LivingFunctionConfig config) {
        for (var entry : config.getComponents()) {
            if (entry.componentClass() == type) {
                return entry.config();
            }
        }
        return ComponentConfig.empty();
    }

    /**
     * 通用辅助：检查输入槽位是否已被占用，若未被占用则标记。
     *
     * @return 如果槽位已被占用返回 true（应跳过此活物品）
     */
    static boolean isInputSlotOccupied(ComponentContext ctx, ContainerContext containerCtx) {
        if (ctx.inputSlot() == -1) return false;
        var occupied = containerCtx.getOccupiedSlots();
        if (occupied == null) return false;
        String slotKey = containerCtx.getStableKey(ctx.inputSlot(), "global_occupancy");
        return occupied.contains(slotKey);
    }

    /**
     * 通用辅助：标记输入槽位为已占用。
     */
    static void markInputSlotOccupied(ComponentContext ctx, ContainerContext containerCtx) {
        if (ctx.inputSlot() == -1) return;
        var occupied = containerCtx.getOccupiedSlots();
        if (occupied == null) return;
        occupied.add(containerCtx.getStableKey(ctx.inputSlot(), "global_occupancy"));
    }
}