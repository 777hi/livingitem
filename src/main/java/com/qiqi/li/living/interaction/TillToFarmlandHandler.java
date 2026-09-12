package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活锄头右键活泥土 → 转换为活耕地（物品转换型处理器）。
 *
 * 逻辑（docs/idea.md「获取方式」「交互处理器设计」）：
 *   - 生存模式：验证光标为活锄头（任意锄头变种 + IS_LIVING），消耗 1 点耐久
 *   - 创造模式：光标物品是客户端虚拟的，服务端无法验证，信任客户端（不消耗）
 *   - 目标槽位物品原地替换：Items.DIRT → Items.FARMLAND，保留 IS_LIVING 标记
 */
public class TillToFarmlandHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack dirt = targetSlot.getItem();
        if (dirt.isEmpty() || !dirt.is(Items.DIRT)) return;
        if (!LivingItemManager.isLivingItem(dirt)) return;

        if (!player.isCreative()) {
            ItemStack carried = player.containerMenu.getCarried();
            if (carried.isEmpty() || !isHoe(carried) || !LivingItemManager.isLivingItem(carried)) return;
            carried.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        }

        // 原地替换物品类型，保留堆叠数与 IS_LIVING 标记
        ItemStack farmland = new ItemStack(Items.FARMLAND, dirt.getCount());
        LivingItemManager.setLiving(farmland, true);
        targetSlot.set(farmland);

        player.containerMenu.broadcastChanges();
    }

    private static boolean isHoe(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.HoeItem;
    }
}
