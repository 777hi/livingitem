package com.qiqi.li.living.mixin;

import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.SimpleContainerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;

/**
 * 创造模式物品栏操作后同步活物品槽位。
 *
 * 创造模式下，客户端通过 ServerboundSetCreativeModeSlotPacket
 * 直接设置服务端背包槽位。该操作绕过正常的容器菜单交互流程，
 * 可能导致活物品的 DataComponent 状态与客户端显示不同步。
 *
 * 此 Mixin 在每次创造模式槽位操作后，强制同步背包中所有活物品槽位，
 * 确保客户端能正确显示活物品的 tooltip 和交互状态。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
    private void onHandleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (player == null) return;

        Inventory inv = player.getInventory();
        int slotNum = packet.slotNum();
        ItemStack stack = inv.getItem(slotNum);

        if (LivingItemManager.isLivingItem(stack)) {
            IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
            if (handler != null) {
                SimpleContainerContext ctx = new SimpleContainerContext(handler, inv);
                ctx.syncSlotToClients(slotNum, stack);
            }
        }
    }
}