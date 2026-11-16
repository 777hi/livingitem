package com.qiqi.li.network;

import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 光标更新；创造活化结果携带菜单与请求身份，旧槽位交互保持无关联调用。 */
public record CarriedUpdatePacket(CompoundTag carriedTag, int menuId, long requestId)
        implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("living_item", "carried_update");
    public static final Type<CarriedUpdatePacket> TYPE = new Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, CarriedUpdatePacket> STREAM_CODEC = StreamCodec.of(
        CarriedUpdatePacket::encode, CarriedUpdatePacket::decode);

    public CarriedUpdatePacket(CompoundTag carriedTag) { this(carriedTag, -1, 0); }

    private static void encode(FriendlyByteBuf buf, CarriedUpdatePacket packet) {
        buf.writeNbt(packet.carriedTag);
        buf.writeVarInt(packet.menuId);
        buf.writeLong(packet.requestId);
    }

    private static CarriedUpdatePacket decode(FriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return new CarriedUpdatePacket(tag != null ? tag : new CompoundTag(), buf.readVarInt(), buf.readLong());
    }

    @Override public Type<CarriedUpdatePacket> type() { return TYPE; }

    private static volatile Consumer<CarriedUpdatePacket> clientReceiver;

    public static void registerClientReceiver(Consumer<CarriedUpdatePacket> receiver) {
        clientReceiver = Objects.requireNonNull(receiver);
    }

    public static void handle(CarriedUpdatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (clientReceiver != null) clientReceiver.accept(packet);
        });
    }
}
