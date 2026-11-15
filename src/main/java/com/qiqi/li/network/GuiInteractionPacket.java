package com.qiqi.li.network;

import com.qiqi.li.living.interaction.InteractionAction;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.MenuActionAccess;
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

/** 当前服务端菜单的精确交互请求；创造光标仅在授权后临时借入执行。 */
public record GuiInteractionPacket(int slotIndex, int containerSlot, String actionId,
                                   @Nullable CompoundTag carriedTag, int menuId)
        implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "gui_interaction");
    public static final Type<GuiInteractionPacket> TYPE = new Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, GuiInteractionPacket> STREAM_CODEC = StreamCodec.of(
        GuiInteractionPacket::encode, GuiInteractionPacket::decode);

    private static void encode(FriendlyByteBuf buf, GuiInteractionPacket pkt) {
        buf.writeVarInt(pkt.slotIndex);
        buf.writeVarInt(pkt.containerSlot);
        buf.writeUtf(pkt.actionId);
        buf.writeBoolean(pkt.carriedTag != null);
        if (pkt.carriedTag != null) buf.writeNbt(pkt.carriedTag);
        buf.writeVarInt(pkt.menuId);
    }

    private static GuiInteractionPacket decode(FriendlyByteBuf buf) {
        int slot = buf.readVarInt();
        int physical = buf.readVarInt();
        String action = buf.readUtf();
        CompoundTag carried = buf.readBoolean() ? buf.readNbt() : null;
        return new GuiInteractionPacket(slot, physical, action, carried, buf.readVarInt());
    }

    @Override
    public Type<GuiInteractionPacket> type() { return TYPE; }

    public static void handle(GuiInteractionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) handle(packet, player);
        });
    }

    /** 拒绝路径不覆盖光标、不维护容器状态；游戏线程与接缝测试使用同一入口。 */
    static void handle(GuiInteractionPacket packet, ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || packet.menuId() != menu.containerId || !menu.stillValid(player)) return;
        Slot target = resolveSlot(menu, packet.slotIndex(), packet.containerSlot());
        if (target == null || !MenuActionAccess.validTarget(player, target)) return;
        InteractionAction action = InteractionRegistry.getAction(packet.actionId());
        if (action == null || !targetIdentityAccepted(packet.actionId(), target.getItem())) return;
        if (action.kind() == InteractionAction.Kind.ITEM_STATE && !isValidTarget(target.getItem())) return;

        ItemStack original = menu.getCarried();
        ItemStack candidate = original;
        boolean virtualCarried = packet.carriedTag() != null;
        if (virtualCarried) {
            if (!player.isCreative() || menu != player.inventoryMenu) return;
            candidate = ItemStack.parse(player.registryAccess(), packet.carriedTag()).orElse(ItemStack.EMPTY);
            if (candidate.isEmpty()) return;
        }
        if (!action.permits(player, target, candidate)) return;

        ItemStack modified = null;
        if (virtualCarried) menu.setCarried(candidate);
        try {
            action.handler().handle(player, target);
            if (player.containerMenu != menu) return;
            com.qiqi.li.living.domain.portal.PortalInteractHandlers.afterInteraction(player, target);
            if (virtualCarried) modified = menu.getCarried().copy();
        } finally {
            if (virtualCarried) menu.setCarried(original);
        }
        if (modified != null) {
            CompoundTag tag = (CompoundTag) modified.saveOptional(player.registryAccess());
            PacketDistributor.sendToPlayer(player, new CarriedUpdatePacket(tag));
        }
        menu.broadcastChanges();
    }

    /** 同号异库存不回退；客户端负责把创造 wrapper 映射到真实服务端槽。 */
    static Slot resolveSlot(AbstractContainerMenu menu, int menuSlot, int physicalSlot) {
        if (menuSlot < 0 || menuSlot >= menu.slots.size()) return null;
        Slot slot = menu.getSlot(menuSlot);
        return slot.isActive() && slot.getContainerSlot() == physicalSlot ? slot : null;
    }

    /** 物品匹配沿规则同源；容器状态动作由已注册的动作范围解析器重验。 */
    static boolean targetIdentityAccepted(String actionId, ItemStack target) {
        boolean found = false;
        for (InteractionEntry entry : InteractionRegistry.getEntries()) {
            if (!entry.actionId().equals(actionId)) continue;
            found = true;
            if (target.isEmpty() || entry.matchesTarget(target)) return true;
        }
        return !found;
    }

    static boolean isValidTarget(ItemStack stack) {
        return !stack.isEmpty() && LivingItemManager.isLivingItem(stack);
    }
}
