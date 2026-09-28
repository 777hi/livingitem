package com.qiqi.li.living.domain.chest;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;
import org.jetbrains.annotations.ApiStatus;

/**
 * 活箱子域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
@ApiStatus.Internal
public final class ChestRegistration {

    private ChestRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingChestFunction());
        ContainerSnapshot.registerProvider(new ChestSnapshotProvider());
    }
}
