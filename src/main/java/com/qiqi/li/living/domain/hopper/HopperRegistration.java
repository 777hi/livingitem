package com.qiqi.li.living.domain.hopper;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;

/**
 * 活漏斗域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class HopperRegistration {

    private HopperRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingHopperFunction());
        ContainerSnapshot.registerProvider(new HopperSnapshotProvider());
    }
}
