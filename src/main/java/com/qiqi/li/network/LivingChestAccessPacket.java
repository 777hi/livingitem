package com.qiqi.li.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;

/**
 * 活箱子存取请求包（客户端→服务端）。
 *
 * 操作类型：
 *   LOAD(0)     — 请求加载活箱子内容，itemTag 和 amount 可忽略
 *   DEPOSIT(1)  — 将光标物品存入活箱子，itemTag 为光标物品NBT，amount 为存入数量
 *   WITHDRAW(2) — 从活箱子取出物品，itemTag 为目标物品NBT，amount 为取出数量
 */
public record LivingChestAccessPacket(int action, @Nullable CompoundTag itemTag, int amount) implements CustomPacketPayload {

    public static final int LOAD = 0;
    public static final int DEPOSIT = 1;
    public static final int WITHDRAW = 2;
    public static final int WITHDRAW_INVENTORY = 3;
    public static final int DEPOSIT_SLOT = 4;

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "living_chest_access");
    public static final CustomPacketPayload.Type<LivingChestAccessPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, LivingChestAccessPacket> STREAM_CODEC = StreamCodec.of(
        LivingChestAccessPacket::encode,
        LivingChestAccessPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, LivingChestAccessPacket pkt) {
        buf.writeVarInt(pkt.action);
        if (pkt.itemTag != null) {
            buf.writeBoolean(true);
            buf.writeNbt(pkt.itemTag);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeVarInt(pkt.amount);
    }

    private static LivingChestAccessPacket decode(FriendlyByteBuf buf) {
        int action = buf.readVarInt();
        CompoundTag tag = null;
        if (buf.readBoolean()) {
            tag = buf.readNbt();
        }
        int amount = buf.readVarInt();
        return new LivingChestAccessPacket(action, tag, amount);
    }

    @Override
    public Type<LivingChestAccessPacket> type() {
        return TYPE;
    }

    public static void handle(LivingChestAccessPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = (net.minecraft.server.level.ServerPlayer) context.player();
            ServerPacketHandler.handleLivingChestAccess(player, packet);
        });
    }
}