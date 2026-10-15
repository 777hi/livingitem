package com.qiqi.li.living.domain.beacon;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活信标 域注册入口（A1 范式 —— 见 {@code RedstoneRegistration} 类注释：
 * 各域自注册，主类不再堆积注册逻辑）。
 *
 * <p>Phase 1：只注册功能本体。交互规则（右键喂锭）/ 遥测段（BeaconSegment）/
 * 箱子宿主同步分别属 Phase 3 / Phase 2-3（见设计稿 §6、§2.7）。</p>
 */
public final class BeaconRegistration {

    private BeaconRegistration() {
    }

    public static void register() {
        LivingItemManager.registerFunction(new BeaconFunction());
    }
}
