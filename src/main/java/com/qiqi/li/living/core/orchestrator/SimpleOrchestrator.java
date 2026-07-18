package com.qiqi.li.living.core.orchestrator;

import java.util.Map;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;

/**
 * 简单编排器 —— 直接遍历所有组件执行 tick，无额外判断。
 *
 * 适用场景：
 * - 活漏斗：传输组件独立工作，无需检查燃料或进度
 * - 任何不需要 canProgress/pauseTick/handleCompletion 的活物品
 *
 * 编排流程：
 * 1. 遍历组件列表
 * 2. 对每个组件调用 tick()
 */
public class SimpleOrchestrator implements LivingOrchestrator {

    public static final SimpleOrchestrator INSTANCE = new SimpleOrchestrator();

    @Override
    public void orchestrate(ComponentContext ctx, int slot, ItemStack stack,
                             Map<String, ComponentState> states,
                             LivingFunctionConfig config, FunctionExecutor fe) {
        LivingOrchestrator.tickAllComponents(ctx, slot, stack, states, config, fe);
    }
}