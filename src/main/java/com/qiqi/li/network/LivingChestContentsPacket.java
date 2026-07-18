package com.qiqi.li.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 活箱子内容同步包（服务端→客户端）。
 *
 * 服务端在以下时机发送此包：
 * 1. 客户端请求加载活箱子内容（LivingChestAccessPacket.LOAD）
 * 2. 活箱子存取操作完成后（DEPOSIT / WITHDRAW）
 *
 * 客户端收到后更新本地缓存，用于配方书标签页渲染。
 */
public record LivingChestContentsPacket(List<CompoundTag> itemTags) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "living_chest_contents");
    public static final CustomPacketPayload.Type<LivingChestContentsPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, LivingChestContentsPacket> STREAM_CODEC = StreamCodec.of(
        LivingChestContentsPacket::encode,
        LivingChestContentsPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, LivingChestContentsPacket pkt) {
        buf.writeVarInt(pkt.itemTags.size());
        for (CompoundTag tag : pkt.itemTags) {
            buf.writeNbt(tag);
        }
    }

    private static LivingChestContentsPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<CompoundTag> tags = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            CompoundTag tag = buf.readNbt();
            if (tag != null) {
                tags.add(tag);
            }
        }
        return new LivingChestContentsPacket(tags);
    }

    @Override
    public Type<LivingChestContentsPacket> type() {
        return TYPE;
    }

    public static void handle(LivingChestContentsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return;

            List<ItemStack> items = new ArrayList<>();
            for (CompoundTag tag : packet.itemTags) {
                ItemStack stack = ItemStack.parse(mc.player.registryAccess(), tag).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    items.add(stack);
                }
            }
            com.qiqi.li.client.LivingChestContentsCache.set(items);
        });
    }
}