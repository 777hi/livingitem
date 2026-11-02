package com.qiqi.li.living.domain.piston;

import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活活塞域注册入口 —— 本域的运行时片段登记（与各域 {@code XxxRegistration} 同款）。
 *
 * <p>⚠️ {@link LivingPistonFunction} <b>暂不全局注册</b>：共享移动后端保证、旧条目 /
 * runtime 失效与首包接缝由框架侧验收后再启用（见 docs/buffer/活活塞.md §7.8.2）。
 * 片段线格式先行登记，不影响「未启用」边界。</p>
 */
public final class PistonRegistration {

    private PistonRegistration() {}

    public static void register() {
        RuntimeSegmentRegistry.register(PistonSegment.INSTANCE);
    }
}
