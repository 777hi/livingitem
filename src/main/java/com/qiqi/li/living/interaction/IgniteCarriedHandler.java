package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.ExplosionComponent;

/**
 * 光标点燃处理器 —— 处理活TNT右键活打火石的交互。
 *
 * 与 IgniteHandler 相反：
 *   - IgniteHandler：光标持有活打火石，右键槽位中的活TNT → 点燃槽位中的TNT
 *   - IgniteCarriedHandler：光标持有活TNT，右键槽位中的活打火石 → 点燃光标上的TNT
 *
 * 逻辑：
 *   - 创造模式：信任客户端，直接点燃光标TNT
 *   - 生存模式：验证槽位中的活打火石，消耗1点耐久，点燃光标TNT
 */
public class IgniteCarriedHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack carried = player.containerMenu.getCarried();

        if (player.isCreative()) {
            if (carried.isEmpty() || !carried.is(Items.TNT)
                || !LivingItemManager.isLivingItem(carried)) return;
        } else {
            ItemStack flintAndSteel = targetSlot.getItem();
            if (flintAndSteel.isEmpty() || !flintAndSteel.is(Items.FLINT_AND_STEEL)
                || !LivingItemManager.isLivingItem(flintAndSteel)) return;

            if (carried.isEmpty() || !carried.is(Items.TNT)
                || !LivingItemManager.isLivingItem(carried)) return;

            flintAndSteel.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        }

        ExplosionComponent.startFuseOnStack(carried);
        player.containerMenu.broadcastChanges();
    }
}