package com.qiqi.li.network;

import com.qiqi.li.LivingItem;
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

import java.util.function.Consumer;

/** 当前服务端菜单的精确交互请求；创造光标仅在授权后临时借入执行。 */
public record GuiInteractionPacket(int slotIndex, int containerSlot, String actionId,
                                   @Nullable CompoundTag carriedTag, int menuId, long requestId)
        implements CustomPacketPayload {
    public GuiInteractionPacket(int slotIndex, int containerSlot, String actionId,
                                @Nullable CompoundTag carriedTag, int menuId) {
        this(slotIndex, containerSlot, actionId, carriedTag, menuId, 0);
    }
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
        buf.writeLong(pkt.requestId);
    }

    private static GuiInteractionPacket decode(FriendlyByteBuf buf) {
        int slot = buf.readVarInt();
        int physical = buf.readVarInt();
        String action = buf.readUtf();
        CompoundTag carried = buf.readBoolean() ? buf.readNbt() : null;
        return new GuiInteractionPacket(slot, physical, action, carried, buf.readVarInt(), buf.readLong());
    }

    @Override
    public Type<GuiInteractionPacket> type() { return TYPE; }

    public static void handle(GuiInteractionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) handle(packet, player);
        });
    }

    /** 关联请求必回实际创造光标；未关联请求保持原有拒绝与同步路径。 */
    static void handle(GuiInteractionPacket packet, ServerPlayer player) {
        Consumer<CarriedUpdatePacket> reply = response -> PacketDistributor.sendToPlayer(player, response);
        if (packet.requestId() > 0) {
            CompoundTag[] result = {packet.carriedTag() == null ? new CompoundTag() : packet.carriedTag().copy()};
            try {
                dispatch(packet, player, response -> result[0] = response.carriedTag());
            } catch (Exception exception) {
                LivingItem.LOGGER.error("服务端：创造光标交互异常，requestId={}", packet.requestId(), exception);
            } finally {
                reply.accept(new CarriedUpdatePacket(result[0], packet.menuId(), packet.requestId()));
            }
        } else {
            dispatch(packet, player, reply);
        }
    }

    private static void dispatch(GuiInteractionPacket packet, ServerPlayer player, Consumer<CarriedUpdatePacket> reply) {
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
            if (virtualCarried) {
                if (packet.requestId() > 0) {
                    CompoundTag tag;
                    try {
                        tag = (CompoundTag) menu.getCarried().copy().saveOptional(player.registryAccess());
                    } catch (Exception exception) {
                        tag = new CompoundTag();
                        LivingItem.LOGGER.error("服务端：创造交互结果编码失败", exception);
                    } finally {
                        menu.setCarried(original);
                    }
                    reply.accept(new CarriedUpdatePacket(tag, packet.menuId(), packet.requestId()));
                } else {
                    menu.setCarried(original);
                }
            }
        }
        if (modified != null && packet.requestId() == 0) {
            CompoundTag tag = (CompoundTag) modified.saveOptional(player.registryAccess());
            reply.accept(new CarriedUpdatePacket(tag));
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
