package com.qiqi.li.network;

import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.interaction.InteractionRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.qiqi.li.living.api.LivingItemManager;

import javax.annotation.Nullable;

/**
 * 通用GUI交互网络包，支持所有活物品的GUI交互。
 *
 * 字段说明：
 *   - slotIndex：客户端菜单槽位索引（用于直接查找）
 *   - containerSlot：容器内实际槽位索引（用于回退查找）
 *   - actionId：交互动作ID（如 "ignite"），服务端通过此ID查找处理器
 *   - carriedTag：光标物品的NBT数据（创造模式下客户端发送，服务端用于恢复光标物品）
 *
 * 创造模式光标物品问题：
 *   创造模式使用 ItemPickerMenu，光标物品是客户端虚拟的，
 *   服务端 menu.getCarried() 返回空。
 *   当交互需要修改光标物品时（如 ignite_carried），
 *   客户端通过 carriedTag 将光标物品数据发送到服务端，
 *   服务端恢复后正常处理，处理完再同步回客户端。
 *
 * 服务端处理流程：
 *   1. 如果 carriedTag 非空且玩家为创造模式，将光标物品设置到服务端菜单
 *   2. 通过 slotIndex 和 containerSlot 定位目标槽位
 *   3. 通过 actionId 查找注册的 InteractionHandler
 *   4. 调用 handler.handle(player, targetSlot)
 *   5. 如果光标物品被修改，同步回客户端
 */
public record GuiInteractionPacket(
    int slotIndex,
    int containerSlot,
    String actionId,
    @Nullable CompoundTag carriedTag
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "gui_interaction");
    public static final CustomPacketPayload.Type<GuiInteractionPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, GuiInteractionPacket> STREAM_CODEC = StreamCodec.of(
        GuiInteractionPacket::encode,
        GuiInteractionPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, GuiInteractionPacket pkt) {
        buf.writeVarInt(pkt.slotIndex);
        buf.writeVarInt(pkt.containerSlot);
        buf.writeUtf(pkt.actionId);
        if (pkt.carriedTag != null) {
            buf.writeBoolean(true);
            buf.writeNbt(pkt.carriedTag);
        } else {
            buf.writeBoolean(false);
        }
    }

    private static GuiInteractionPacket decode(FriendlyByteBuf buf) {
        int slotIndex = buf.readVarInt();
        int containerSlot = buf.readVarInt();
        String actionId = buf.readUtf();
        CompoundTag carriedTag = null;
        if (buf.readBoolean()) {
            carriedTag = buf.readNbt();
        }
        return new GuiInteractionPacket(slotIndex, containerSlot, actionId, carriedTag);
    }

    @Override
    public Type<GuiInteractionPacket> type() {
        return TYPE;
    }

    public static void handle(GuiInteractionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            AbstractContainerMenu menu = player.containerMenu;

            boolean carriedRestored = false;
            if (player.isCreative() && packet.carriedTag() != null) {
                ItemStack carried = ItemStack.parse(player.registryAccess(), packet.carriedTag())
                    .orElse(ItemStack.EMPTY);
                if (!carried.isEmpty()) {
                    menu.setCarried(carried);
                    carriedRestored = true;
                }
            }

            Slot targetSlot = resolveSlot(menu, packet.slotIndex(), packet.containerSlot());
            if (targetSlot == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            InteractionHandler handler = InteractionRegistry.getHandler(packet.actionId());
            if (handler == null) {
                if (carriedRestored) menu.setCarried(ItemStack.EMPTY);
                return;
            }

            handler.handle(player, targetSlot);

            if (carriedRestored) {
                ItemStack modifiedCarried = menu.getCarried().copy();
                menu.setCarried(ItemStack.EMPTY);

                // 使用自定义包同步光标物品，绕过原版对 CreativeModeInventoryScreen 的排除
                // 原版 ClientboundContainerSetSlotPacket(-1, -1) 在创造模式下被忽略
                CompoundTag carriedSyncTag = (CompoundTag) modifiedCarried.saveOptional(player.registryAccess());
                PacketDistributor.sendToPlayer(player, new CarriedUpdatePacket(carriedSyncTag));
            }

            menu.broadcastChanges();
        });
    }

    /**
     * 定位目标槽位，兼容创造模式的索引差异。
     * 优先通过 slotIndex 直接查找，失败则通过 containerSlot 遍历查找。
     */
    private static Slot resolveSlot(AbstractContainerMenu menu, int slotIndex, int containerSlot) {
        if (slotIndex >= 0 && slotIndex < menu.slots.size()) {
            Slot directSlot = menu.getSlot(slotIndex);
            ItemStack stack = directSlot.getItem();
            if (!stack.isEmpty() && LivingItemManager.isLivingItem(stack)) {
                return directSlot;
            }
        }

        for (Slot s : menu.slots) {
            if (s.getContainerSlot() == containerSlot) {
                ItemStack stack = s.getItem();
                if (!stack.isEmpty() && LivingItemManager.isLivingItem(stack)) {
                    return s;
                }
            }
        }

        return null;
    }
}