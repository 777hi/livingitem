package com.qiqi.li.living.domain.sponge;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活海绵域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class SpongeRegistration {

    private SpongeRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingSpongeFunction());
        // GUI 汲（第三段）：桶点海绵槽 —— 服务端权威重验；客户端分支在 GuiInteractionHelper
        com.qiqi.li.living.interaction.InteractionRegistry.registerHandler(
            "living_sponge_draw", LivingSpongeInteractHandlers.DRAW);
        // 容器级能力边沿（⑭，2026-11-02）：存在性（有湿活海绵）翻转 ⇒ invalidateCapabilities
        // —— 匿名类注册（ContainerTickHook 两个方法全 default，lambda 不适用）
        com.qiqi.li.living.container.ContainerTickHooks.register(
            new com.qiqi.li.living.container.ContainerTickHook() {
                @Override
                public void onTickStart(com.qiqi.li.living.container.ContainerContext ctx,
                                        com.qiqi.li.living.container.TickContext tick) {
                    SpongeContainerFluidHandler.tickEdge(ctx);
                }
            });
        // SpongeItemFluidHandler（ITEM）与 SpongeContainerFluidHandler（BLOCK，⑭）的
        // 能力注册在 LivingItem#onRegisterCapabilities（与实现同拍）
    }
}
