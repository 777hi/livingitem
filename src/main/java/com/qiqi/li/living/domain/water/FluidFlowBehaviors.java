package com.qiqi.li.living.domain.water;

import java.util.HashMap;
import java.util.Map;

import net.neoforged.neoforge.fluids.FluidType;

/**
 * 流体流动行为注册表（1b-2 契约）—— 框架提供，流体侧填。
 *
 * <p>容器内流体引擎通过 {@link #of(FluidType)} 查行为；<b>未注册的流体 = 静止</b>
 * （{@link FluidFlowBehavior#STATIC}）⇒ 安全默认：不会把没声明过的流体误当成会流。</p>
 *
 * <p>用法（流体侧）：{@code FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
 * FluidFlowBehavior.flowing(7, 0));}</p>
 */
public final class FluidFlowBehaviors {

    private static final Map<FluidType, FluidFlowBehavior> REGISTRY = new HashMap<>();

    private FluidFlowBehaviors() {}

    /** 注册某流体的流动行为（重复注册以后者为准）。 */
    public static void register(FluidType fluid, FluidFlowBehavior behavior) {
        if (fluid == null || behavior == null) return;
        REGISTRY.put(fluid, behavior);
    }

    /** 查某流体的流动行为；未注册返回 {@link FluidFlowBehavior#STATIC}。 */
    public static FluidFlowBehavior of(FluidType fluid) {
        return REGISTRY.getOrDefault(fluid, FluidFlowBehavior.STATIC);
    }

    /** 清空注册表（单测用 —— 静态注册表必须可显式 reset）。 */
    public static void clear() {
        REGISTRY.clear();
    }
}
