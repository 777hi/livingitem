package com.qiqi.li.living.domain.water;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活桶汲/倒处理器（流体侧批次二，2026-10-03）—— 薄壳：光标校验 + 委托
 * {@link LivingBucketInteractSupport}（活流体数据反查与内容增减）。
 *
 * <p>注册：{@code WaterRegistration} → {@code living_bucket_pour} / {@code living_bucket_scoop}。
 * 客户端拦截不走交互规则 JSON（目标条件是「空槽位 + 容器级源状态」，物品中心规则表达不了），
 * 由 {@code GuiInteractionHelper} 的活桶分支按流体快照精确判定后直接发
 * {@code GuiInteractionPacket}，此处服务端<b>权威重验</b>一切前置条件。</p>
 */
public final class LivingBucketInteractHandlers {

    /** 倒水：光标满活桶 + 目标格无物品 → 诞生派生源（已有源则源不变）+ 桶排空一桶。 */
    public static final InteractionHandler POUR = (player, slot) ->
        interact(player, slot, true);

    /** 汲水：光标空活桶 + 目标格是源（level 0）→ 源消失 + 桶灌入一桶。 */
    public static final InteractionHandler SCOOP = (player, slot) ->
        interact(player, slot, false);

    private LivingBucketInteractHandlers() {}

    private static void interact(ServerPlayer player, Slot slot, boolean pour) {
        ItemStack carried = player.containerMenu.getCarried();
        if (!LivingBucketFunction.isLivingBucket(carried)) return;

        if (pour) {
            if (!LivingBucketFunction.hasFullBucket(carried)) return;
            if (!slot.getItem().isEmpty()) return;   // 有物品 = 原版放置语义，不倒
            LivingBucketInteractSupport.pour(player, slot, carried);
            // 创造模式对齐原版无限桶：倒水不消耗（光标保持满桶）
            if (player.hasInfiniteMaterials()) {
                player.containerMenu.setCarried(carried);
            }
        } else {
            if (!LivingBucketFunction.isEmptyBucket(carried)) return;
            LivingBucketInteractSupport.scoop(player, slot, carried);
        }
    }
}
