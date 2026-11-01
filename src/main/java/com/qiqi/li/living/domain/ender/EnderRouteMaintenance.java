package com.qiqi.li.living.domain.ender;

import java.util.Map;
import java.util.Set;

import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

/**
 * 末影路由的写回阶段维护 —— 每容器 tick 校验并回收本容器关联的失效路由。
 *
 * <p>挂在 writeback 钩子上，取代原先「漏斗 tick 与末影箱 tick 互传对方槽位数据」的
 * 镜像双调用：注册方槽位由路由表按 {@code RouteRegistrar} 标记自查（不 import 漏斗域），
 * 末影箱槽位与频道键由本领域自给（{@code getFunctionSlots} 引自己的 ID）。
 * 两类失效（注册方离场 / 末影箱离场）由此一处全覆盖。</p>
 */
final class EnderRouteMaintenance implements ContainerTickHook {

    @Override
    public void onWriteback(TickableContainerContext ctx, TickContext tick) {
        Set<Integer> enderSlots = tick.getFunctionSlots(LivingEnderChestFunction.ID);
        Map<Integer, EnderChannelKey> targetKeysBySlot = EnderChannelKey.ofSlots(ctx, enderSlots);
        EnderChannelRegistry.getInstance().validateRoutes(ctx, targetKeysBySlot);
    }
}
