package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerTickHooks;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 红电（电力层）域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class PowerRegistration {

    private PowerRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingWaxedCopperFunction());
        // 运行时片段：发电机把「检测仪表盘」的展示数据交给 runtime 机制（档 2）
        RuntimeSegmentRegistry.register(GeneratorSegment.INSTANCE);
        // 框架中继：相位快照写回钩子（2026-10-08 计划 ⑤）——
        // 原逻辑硬编码在 ContainerLivingItemHandler#writebackBlockEntities，现移入本领域。
        ContainerTickHooks.register(new ContainerPhaseWriteback());
    }
}
