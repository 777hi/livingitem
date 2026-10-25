package com.qiqi.li.network;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.container.ContainerKind;
import com.qiqi.li.living.container.ContainerLayout;
import com.qiqi.li.living.container.ContainerMenuInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 当前菜单的只读布局，不携带容器物品内容。 */
public record ContainerMenuInfoPacket(int menuId, long requestId, ContainerMenuInfo info)
        implements CustomPacketPayload {
    public static final Type<ContainerMenuInfoPacket> TYPE = new Type<>(LivingItem.id("container_menu_info"));
    public static final StreamCodec<FriendlyByteBuf, ContainerMenuInfoPacket> STREAM_CODEC =
        StreamCodec.of(ContainerMenuInfoPacket::encode, ContainerMenuInfoPacket::decode);

    private static void encode(FriendlyByteBuf buf, ContainerMenuInfoPacket packet) {
        buf.writeVarInt(packet.menuId);
        buf.writeLong(packet.requestId);
        buf.writeEnum(packet.info.status());
        if (packet.info.status() == ContainerMenuInfo.Status.AVAILABLE) {
            buf.writeEnum(packet.info.kind());
            buf.writeVarInt(packet.info.size());
            buf.writeVarInt(packet.info.layout().columns());
            buf.writeEnum(packet.info.layout().source());
            buf.writeUtf(packet.info.typeId());
        }
    }

    private static ContainerMenuInfoPacket decode(FriendlyByteBuf buf) {
        int menuId = buf.readVarInt();
        long requestId = buf.readLong();
        var status = buf.readEnum(ContainerMenuInfo.Status.class);
        ContainerMenuInfo info = switch (status) {
            case NON_HOST -> ContainerMenuInfo.NON_HOST;
            case UNAVAILABLE -> ContainerMenuInfo.UNAVAILABLE;
            case AVAILABLE -> new ContainerMenuInfo(status, buf.readEnum(ContainerKind.class),
                buf.readVarInt(), new ContainerLayout(buf.readVarInt(),
                    buf.readEnum(ContainerLayout.Source.class)), buf.readUtf());
        };
        return new ContainerMenuInfoPacket(menuId, requestId, info);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static volatile java.util.function.Consumer<ContainerMenuInfoPacket> clientReceiver;

    public static void registerClientReceiver(java.util.function.Consumer<ContainerMenuInfoPacket> receiver) {
        clientReceiver = java.util.Objects.requireNonNull(receiver);
    }

    public static void handle(ContainerMenuInfoPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (clientReceiver != null) clientReceiver.accept(packet);
        });
    }
}
