package com.qiqi.li.living.domain.tools;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活工具 / 活武器域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>活【工具】与活【武器】共用 {@link LivingToolFunction}（行为差异在 tick 里按记忆类型分派），
 * 因此这里只注册一个 Function。
 */
public final class ToolRegistration {

    private ToolRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingToolFunction());
    }
}
