package com.qiqi.li.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.qiqi.li.LivingItem;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record HopperDirectionPacket(
    CompoundTag mappingData
) implements CustomPacketPayload {

    public static final Type<HopperDirectionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "hopper_direction_v2"));

    public static final StreamCodec<FriendlyByteBuf, HopperDirectionPacket> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public HopperDirectionPacket decode(FriendlyByteBuf buf) {
                CompoundTag tag = buf.readNbt();
                return new HopperDirectionPacket(tag != null ? tag : new CompoundTag());
            }

            @Override
            public void encode(FriendlyByteBuf buf, HopperDirectionPacket pkt) {
                buf.writeNbt(pkt.mappingData());
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HopperDirectionPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                ServerPacketHandler.handleHopperDirection(serverPlayer, payload);
            }
        });
    }
}