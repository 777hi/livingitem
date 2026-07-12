package com.qiqi.li.living.core.orchestrator;

import java.util.Map;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.FuelConsumeComponent;
import com.qiqi.li.living.core.components.ILivingComponent;
import com.qiqi.li.living.core.components.ItemTransformComponent;
import com.qiqi.li.living.core.components.ProgressComponent;

/**
 * 燃料+进度编排器 —— 检查燃料和输入有效性，管理进度推进/回退，完成时执行转化。
 *
 * 适用场景：
 * - 活熔炉：有燃料消耗、进度推进和物品转化
 * - 活酿造台：类似活熔炉但使用不同配方类型
 * - 任何需要燃料+进度+转化的活物品
 *
 * 编排流程：
 * 1. 检查输入槽位是否已被占用（跳过已占用的）
 * 2. 检查是否可以继续处理（燃料有效性 + 输入有效性）
 * 3. 可以继续 → 正常 tick 所有组件
 *    无法继续 → pauseTick 进度和燃料组件（模拟余热消散）
 * 4. 进度完成且燃料仍在燃烧 → 执行转化并重置进度
 * 5. 标记输入槽位为已占用
 *
 * 暂停回退机制：
 * - 进度回退：每 tick -1，避免进度卡在临界值
 * - 燃料回退：每 tick -1，防止无输入时燃料白白消耗
 */
public class FuelProgressOrchestrator implements LivingOrchestrator {

    public static final FuelProgressOrchestrator INSTANCE = new FuelProgressOrchestrator();

    @Override
    public void orchestrate(ComponentContext ctx, int slot, ItemStack stack,
                             Map<String, ComponentState> states,
                             LivingFunctionConfig config, FunctionExecutor fe) {

        if (LivingOrchestrator.isInputSlotOccupied(ctx, ctx.containerCtx())) return;

        FuelConsumeComponent fuelComp = fe.findComponent(config, FuelConsumeComponent.class);
        ProgressComponent progressComp = fe.findComponent(config, ProgressComponent.class);
        ItemTransformComponent transformComp = fe.findComponent(config, ItemTransformComponent.class);

        ComponentConfig fuelConfig = LivingOrchestrator.findConfig(FuelConsumeComponent.class, config);

        boolean canProgress = checkCanProgress(fuelComp, transformComp, ctx, states, fuelConfig);

        if (canProgress) {
            LivingOrchestrator.tickAllComponents(ctx, slot, stack, states, config, fe);
        } else {
            pauseTickComponents(fuelComp, progressComp, ctx, slot, stack, states, config, fe);
        }

        handleCompletion(fuelComp, progressComp, transformComp, ctx, stack, states, config);

        LivingOrchestrator.markInputSlotOccupied(ctx, ctx.containerCtx());
    }

    private boolean checkCanProgress(FuelConsumeComponent fuelComp, ItemTransformComponent transformComp,
                                      ComponentContext ctx, Map<String, ComponentState> states,
                                      ComponentConfig fuelConfig) {
        if (fuelComp != null) {
            ComponentState fuelState = states.get(fuelComp.getComponentId());
            if (!fuelComp.isBurning(fuelState) && !fuelComp.hasUsableFuel(ctx, fuelConfig)) {
                return false;
            }
        }

        if (transformComp != null) {
            ComponentState transformState = states.get(transformComp.getComponentId());
            if (!transformComp.canProcess(ctx, transformState)) {
                return false;
            }
        }

        return true;
    }

    private void pauseTickComponents(FuelConsumeComponent fuelComp, ProgressComponent progressComp,
                                     ComponentContext ctx, int slot, ItemStack stack,
                                     Map<String, ComponentState> states,
                                     LivingFunctionConfig config, FunctionExecutor fe) {
        for (var entry : config.getComponents()) {
            ILivingComponent component = fe.resolveComponent(config, entry);
            ComponentState state = states.get(component.getComponentId());

            if (component instanceof ProgressComponent pc) {
                pc.pauseTick(state);
            } else if (component instanceof FuelConsumeComponent fc) {
                fc.pauseTick(state);
            } else {
                component.tick(ctx, slot, stack, state, entry.config());
            }
        }
    }

    private void handleCompletion(FuelConsumeComponent fuelComp, ProgressComponent progressComp,
                                  ItemTransformComponent transformComp,
                                  ComponentContext ctx, ItemStack stack,
                                  Map<String, ComponentState> states,
                                  LivingFunctionConfig config) {
        if (progressComp == null || transformComp == null) return;

        ComponentConfig progressConfig = LivingOrchestrator.findConfig(ProgressComponent.class, config);
        ComponentConfig transformConfig = LivingOrchestrator.findConfig(ItemTransformComponent.class, config);

        ComponentState progressState = states.get(progressComp.getComponentId());
        if (!progressComp.isComplete(progressState, progressConfig)) return;

        ComponentState fuelState = fuelComp != null ? states.get(fuelComp.getComponentId()) : null;
        if (fuelComp != null && !fuelComp.isBurning(fuelState)) return;

        ComponentState transformState = states.get(transformComp.getComponentId());
        boolean success = transformComp.executeTransform(ctx, stack, transformConfig, progressComp, transformState);
        if (success) {
            progressComp.reset(progressState);
        }
    }
}