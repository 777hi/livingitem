package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.ExplosionComponent;

/**
 * 点燃交互处理器 —— 处理活打火石右键活TNT的交互。
 *
 * 逻辑：
 *   - 创造模式：光标物品是客户端虚拟的，服务端无法验证，信任客户端
 *   - 生存模式：验证光标持有活打火石，并消耗1点耐久
 *   - 调用 ExplosionComponent.startFuseOnStack() 启动引信倒计时
 *   - 同步菜单数据到客户端
 */
public class IgniteHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        if (player.isCreative()) {
            // 创造模式光标物品是纯客户端虚拟的，服务端无法验证，信任客户端
        } else {
            ItemStack carried = player.containerMenu.getCarried();
            if (carried.isEmpty() || !carried.is(Items.FLINT_AND_STEEL)
                || !LivingItemManager.isLivingItem(carried)) return;
            carried.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }

        ExplosionComponent.startFuseOnStack(targetSlot.getItem());
        player.containerMenu.broadcastChanges();
    }
}