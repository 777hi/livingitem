package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活地图传送域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>本域除了 Function 还有两个<b>事件监听器</b>的注册
 * （{@code LivingMapEventHandler} / {@code ItemFrameMapTeleportHandler}），
 * 它们同样从 {@code commonSetup} 搬到这里。
 */
public final class MapRegistration {

    private MapRegistration() {}

    public static void register() {
        // ── 功能 ──
        LivingItemManager.registerFunction(new LivingEnderPearlFunction());
        LivingItemManager.registerFunction(new LivingMapFunction());

        // ── 世界事件监听 ──
        LivingMapEventHandler.register();
        ItemFrameMapTeleportHandler.register();
    }
}
