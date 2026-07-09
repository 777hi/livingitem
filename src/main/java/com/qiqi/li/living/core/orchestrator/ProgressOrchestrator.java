package com.qiqi.li.living.core.orchestrator;

import java.util.Map;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.ILivingComponent;
import com.qiqi.li.living.core.components.ItemTransformComponent;
import com.qiqi.li.living.core.components.ProgressComponent;

/**
 * 进度编排器 —— 检查输入有效性，管理进度推进/回退，完成时执行转化。
 *
 * 适用场景：
 * - 活磨石：有进度和转化，但无燃料消耗
 * - 任何需要 canProgress/pauseTick/handleCompletion 但无燃料的活物品
 *
 * 编排流程：
 * 1. 检查输入槽位是否已被占用（跳过已占用的）
 * 2. 检查是否可以继续处理（输入有效性 + 转化组件 canProcess）
 * 3. 可以继续 → 正常 tick 所有组件
 *    无法继续 → pauseTick 进度组件（模拟余热消散）
 * 4. 进度完成时 → 执行转化并重置进度
 * 5. 标记输入槽位为已占用
 */
public class ProgressOrchestrator implements LivingOrchestrator {

    public static final ProgressOrchestrator INSTANCE = new ProgressOrchestrator();

    @Override
    public void orchestrate(ComponentContext ctx, int slot, ItemStack stack,
                             Map<String, ComponentState> states,
                             LivingFunctionConfig config, FunctionExecutor fe) {

        if (LivingOrchestrator.isInputSlotOccupied(ctx, ctx.containerCtx())) return;

        ProgressComponent progressComp = fe.findComponent(config, ProgressComponent.class);
        ItemTransformComponent transformComp = fe.findComponent(config, ItemTransformComponent.class);

        boolean canProgress = checkCanProgress(transformComp, ctx);

        if (canProgress) {
            LivingOrchestrator.tickAllComponents(ctx, slot, stack, states, config, fe);
        } else {
            pauseTickComponents(progressComp, ctx, slot, stack, states, config, fe);
        }

        handleCompletion(progressComp, transformComp, ctx, stack, states, config, fe);

        LivingOrchestrator.markInputSlotOccupied(ctx, ctx.containerCtx());
    }

    private boolean checkCanProgress(ItemTransformComponent transformComp, ComponentContext ctx) {
        if (transformComp != null && !transformComp.canProcess(ctx)) {
            return false;
        }
        return true;
    }

    private void pauseTickComponents(ProgressComponent progressComp,
                                     ComponentContext ctx, int slot, ItemStack stack,
                                     Map<String, ComponentState> states,
                                     LivingFunctionConfig config, FunctionExecutor fe) {
        for (var entry : config.getComponents()) {
            ILivingComponent component = fe.resolveComponent(config, entry);
            ComponentState state = states.get(component.getComponentId());

            if (component instanceof ProgressComponent pc) {
                pc.pauseTick(state);
            } else {
                component.tick(ctx, slot, stack, state, entry.config());
            }
        }
    }

    private void handleCompletion(ProgressComponent progressComp, ItemTransformComponent transformComp,
                                  ComponentContext ctx, ItemStack stack,
                                  Map<String, ComponentState> states,
                                  LivingFunctionConfig config, FunctionExecutor fe) {
        if (progressComp == null || transformComp == null) return;

        ComponentConfig progressConfig = LivingOrchestrator.findConfig(ProgressComponent.class, config);
        ComponentConfig transformConfig = LivingOrchestrator.findConfig(ItemTransformComponent.class, config);

        ComponentState progressState = states.get(progressComp.getComponentId());
        if (!progressComp.isComplete(progressState, progressConfig)) return;

        ComponentState transformState = states.get(transformComp.getComponentId());
        boolean success = transformComp.executeTransform(ctx, stack, transformConfig, progressComp, transformState);
        if (success) {
            progressComp.reset(progressState);
        }
    }
}