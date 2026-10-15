package com.qiqi.li.living.domain.beacon;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活信标 域注册入口（A1 范式 —— 见 {@code RedstoneRegistration} 类注释）。
 *
 * <p>Phase 2：功能 + 运行时片段（遥测）。交互规则（右键喂锭）属 Phase 3。</p>
 */
public final class BeaconRegistration {

    private BeaconRegistration() {
    }

    public static void register() {
        LivingItemManager.registerFunction(new BeaconFunction());
        // 运行时片段：激活等级 / 柱高 / 再生档的展示数据（档 2）
        RuntimeSegmentRegistry.register(BeaconSegment.INSTANCE);
    }
}
