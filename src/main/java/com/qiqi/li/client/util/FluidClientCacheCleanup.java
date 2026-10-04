package com.qiqi.li.client.util;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.water.FluidFlowClientCache;

/**
 * 客户端断连兜底清理（2026-10-04，游戏实测「渲染跨存档残留」）。
 *
 * <p>界面 {@code removed()} 清缓存与「退出存档」之间存在竞态窗口：服务端最后一拍
 * 仍可能把玩家当 viewer 发出快照包，客户端包处理器不校验界面状态直接写缓存
 * ⇒ 缓存带着上一个存档的数据进入新世界 ⇒ 同坐标容器首次打开渲染旧水、
 * 重开才消失。{@code LoggingOut} 在断线时刻触发，覆盖一切时序。</p>
 */
@EventBusSubscriber
public final class FluidClientCacheCleanup {

    private FluidClientCacheCleanup() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FluidFlowClientCache.clear();
        LivingItemClientCache.clear();   // 运行时遥测缓存同病同修（removed() 与断线同竞态）
    }
}
