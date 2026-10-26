package com.qiqi.li.living.domain.tnt;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.InteractionHandler;

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
        // ⚠️ 创造分支改「验在即有」（2026-10-27 审查 P3）：「光标纯客户端虚拟」只对
        //    创造背包页（ItemPickerMenu，carriedTag 回灌）成立；创造模式打开箱子等普通
        //    容器时光标由服务端权威管理、可验。曾无条件跳过校验 ⇒ 改包客户端在创造模式
        //    可空手点燃任意活 TNT（同域 IgniteCarriedHandler 本就照验，口径对齐）。
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty() && (!carried.is(Items.FLINT_AND_STEEL)
                || !LivingItemManager.isLivingItem(carried))) {
            return;   // 光标「有但不是活打火石」＝ 拒绝（无论模式）
        }
        if (!player.isCreative()) {
            carried.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }

        ExplosionComponent.startFuseOnStack(targetSlot.getItem());
        // ⚠️ 就地改写组件必须经槽位标脏（2026-10-27 审查 P3-4）：NBT 背书 handler 的
        //    就地改不触发保存钩子，点燃与下一 tick 写回之间若恰好落盘会丢引信状态。
        targetSlot.setChanged();
        player.containerMenu.broadcastChanges();
    }
}