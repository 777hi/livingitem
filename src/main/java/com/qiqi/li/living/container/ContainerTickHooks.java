package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器 tick 钩子注册表 —— 见 {@link ContainerTickHook} 的类注释。
 *
 * <p>注册时机：各领域在 {@code LivingItem#commonSetup} 经由自己的 {@code *Registration}
 * 注册一次。按类去重，避免模组重复初始化时叠加多份。</p>
 */
public final class ContainerTickHooks {

    private static final List<ContainerTickHook> HOOKS = new ArrayList<>();

    private ContainerTickHooks() {}

    /** 注册钩子（按类去重）。 */
    public static void register(ContainerTickHook hook) {
        for (ContainerTickHook h : HOOKS) {
            if (h.getClass() == hook.getClass()) return;
        }
        HOOKS.add(hook);
    }

    /** 触发 tick 开始阶段（由 {@code SimpleContainerContext#setTickContext} 调用）。 */
    public static void fireTickStart(ContainerContext ctx, TickContext tick) {
        for (ContainerTickHook h : HOOKS) {
            h.onTickStart(ctx, tick);
        }
    }

    /** 触发写回阶段（由 {@code ContainerLivingItemHandler} 调用）。 */
    public static void fireWriteback(TickableContainerContext ctx, TickContext tick) {
        for (ContainerTickHook h : HOOKS) {
            h.onWriteback(ctx, tick);
        }
    }
}
