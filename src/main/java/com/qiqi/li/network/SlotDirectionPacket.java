package com.qiqi.li.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.qiqi.li.LivingItem;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 活物品槽位方向配置网络包（用于 SLOTS 模式，如活熔炉的 input/fuel/output）。
 *
 * 功能：
 * 将客户端的活熔炉槽位方向修改请求发送到服务端，
 * 由服务端更新活熔炉的 NBT 数据并同步回客户端。
 *
 * 数据格式：
 * - functionId：功能 ID（如 "living_furnace"）
 * - slotName：槽位名称（如 "input"、"fuel"、"output"）
 * - directionX：方向 X 偏移
 * - directionY：方向 Y 偏移
 *
 * 通信流程：
 *   客户端 LivingItemInputHandler → PacketDistributor.sendToServer()
 * → 服务端 ServerPacketHandler.handleSlotDirection()
 * → 更新光标物品 NBT → 同步到客户端
 */
public record SlotDirectionPacket(
    String functionId,
    String slotName,
    int directionX,
    int directionY
) implements CustomPacketPayload {

    public static final Type<SlotDirectionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "slot_direction"));

    public static final StreamCodec<FriendlyByteBuf, SlotDirectionPacket> STREAM_CODEC = StreamCodec.of(
        SlotDirectionPacket::encode,
        SlotDirectionPacket::decode
    );

    private static void encode(FriendlyByteBuf buf, SlotDirectionPacket pkt) {
        buf.writeUtf(pkt.functionId());
        buf.writeUtf(pkt.slotName());
        buf.writeInt(pkt.directionX());
        buf.writeInt(pkt.directionY());
    }

    private static SlotDirectionPacket decode(FriendlyByteBuf buf) {
        return new SlotDirectionPacket(
            buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SlotDirectionPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                ServerPacketHandler.handleSlotDirection(serverPlayer, payload);
            }
        });
    }
}