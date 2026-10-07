package com.qiqi.li.living.domain.furnace;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活熔炉域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class FurnaceRegistration {

    private FurnaceRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingFurnaceFunction());
        // 运行时片段：熔炉把「烧制进度」的展示数据交给 runtime 机制（档 2）
        RuntimeSegmentRegistry.register(FurnaceSegment.INSTANCE);
    }
}
