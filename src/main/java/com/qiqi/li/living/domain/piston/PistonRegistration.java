package com.qiqi.li.living.domain.piston;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活活塞域注册入口 —— 功能 + 运行时片段（与各域 {@code XxxRegistration} 同款）。
 *
 * <p>普通推 / 粘性拉由本域执行；机械压送经共享接收端口分发，活塞域不依赖具体接收领域。</p>
 */
public final class PistonRegistration {

    private PistonRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingPistonFunction());
        RuntimeSegmentRegistry.register(PistonSegment.INSTANCE);
    }
}
