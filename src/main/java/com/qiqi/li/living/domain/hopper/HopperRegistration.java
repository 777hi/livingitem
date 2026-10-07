package com.qiqi.li.living.domain.hopper;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活漏斗域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class HopperRegistration {

    private HopperRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingHopperFunction());
        ContainerSnapshot.registerProvider(new HopperSnapshotProvider());
        // 运行时片段：漏斗把「冷却 + 槽位解析」的展示数据交给 runtime 机制（档 2）
        RuntimeSegmentRegistry.register(HopperSegment.INSTANCE);
    }
}
