package com.qiqi.li.living.domain.sponge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活海绵 GUI 汲处理器（设计稿 §2.3，第三段）—— 薄壳：光标校验 + 委托
 * {@link LivingSpongeInteractSupport}。注册：{@code SpongeRegistration} →
 * {@code living_sponge_draw}。客户端拦截不走交互规则 JSON（目标是「组件量 ≥ 一桶」，
 * 物品中心规则表达不了），由 {@code GuiInteractionHelper} 的海绵分支按组件精确判定后
 * 直接发 {@code GuiInteractionPacket}，此处服务端<b>权威重验</b>一切前置条件
 * （{@code LivingBucketInteractHandlers} 同款形态）。
 */
public final class LivingSpongeInteractHandlers {

    /** 汲出：光标活空桶 + 目标槽湿活海绵（堆总量 ≥ 一桶）⇒ 海绵均分扣一桶 + 桶灌满。 */
    public static final InteractionHandler DRAW = (ServerPlayer player, Slot slot) -> {
        ItemStack carried = player.containerMenu.getCarried();
        if (!LivingSpongeInteractSupport.isLivingEmptyBucket(carried)) return;
        LivingSpongeInteractSupport.drawFromSponge(player, slot, carried);
    };

    private LivingSpongeInteractHandlers() {}
}
