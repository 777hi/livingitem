package com.qiqi.li.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 光标物品更新包（服务端→客户端）。
 *
 * 解决的问题：
 *   原版 ClientboundContainerSetSlotPacket(containerId=-1, slot=-1) 在
 *   ClientPacketListener.handleContainerSetSlot 中明确排除了 CreativeModeInventoryScreen：
 *
 *     if (packet.getContainerId() == -1) {
 *         if (!(this.minecraft.screen instanceof CreativeModeInventoryScreen)) {
 *             player.containerMenu.setCarried(itemstack);
 *         }
 *     }
 *
 *   因此创造模式下无法通过原版包更新光标物品。本包绕过此限制，
 *   客户端收到后直接更新当前屏幕菜单的 carried，无论是否为创造模式。
 *
 * 使用场景：
 *   当交互处理器修改了光标物品（如 ignite_carried 点燃光标上的活TNT），
 *   服务端需要将修改后的光标物品同步到客户端。
 */
public record CarriedUpdatePacket(CompoundTag carriedTag) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "carried_update");
    public static final CustomPacketPayload.Type<CarriedUpdatePacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, CarriedUpdatePacket> STREAM_CODEC = StreamCodec.of(
        CarriedUpdatePacket::encode,
        CarriedUpdatePacket::decode
    );

    private static void encode(FriendlyByteBuf buf, CarriedUpdatePacket pkt) {
        buf.writeNbt(pkt.carriedTag);
    }

    private static CarriedUpdatePacket decode(FriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return new CarriedUpdatePacket(tag != null ? tag : new CompoundTag());
    }

    @Override
    public Type<CarriedUpdatePacket> type() {
        return TYPE;
    }

    public static void handle(CarriedUpdatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            ItemStack carried = ItemStack.parse(mc.player.registryAccess(), packet.carriedTag())
                .orElse(ItemStack.EMPTY);

            // 创造模式：更新 ItemPickerMenu 的光标（原版包不会更新它）
            // 非创造模式：更新 containerMenu 的光标
            if (mc.screen instanceof CreativeModeInventoryScreen screen) {
                screen.getMenu().setCarried(carried);
            } else {
                mc.player.containerMenu.setCarried(carried);
            }
        });
    }
}