package com.qiqi.li.network;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.container.ContainerMenuInfos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 只读当前菜单信息；不接受客户端指定位置或容器身份。 */
@net.neoforged.fml.common.EventBusSubscriber(modid = "living_item")
public record ContainerMenuInfoRequestPacket(int menuId, long requestId) implements CustomPacketPayload {
    public static final Type<ContainerMenuInfoRequestPacket> TYPE =
        new Type<>(LivingItem.id("container_menu_info_request"));
    public static final StreamCodec<FriendlyByteBuf, ContainerMenuInfoRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.menuId);
            buf.writeLong(value.requestId);
        }, buf -> new ContainerMenuInfoRequestPacket(buf.readVarInt(), buf.readLong()));

    private static final java.util.Map<java.util.UUID, Long> LAST_REQUEST = new java.util.HashMap<>();
    static {
        com.qiqi.li.living.util.StaticCacheRegistry.onServerStop(server -> LAST_REQUEST.clear());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) LAST_REQUEST.remove(player.getUUID());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    static boolean allowRequest(java.util.UUID playerId, long now) {
        Long last = LAST_REQUEST.get(playerId);
        if (last != null && now >= last && now - last < 10) return false;
        LAST_REQUEST.put(playerId, now);
        return true;
    }

    public static void handle(ContainerMenuInfoRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.containerMenu == null || player.containerMenu.containerId != packet.menuId) return;
            if (!allowRequest(player.getUUID(), player.level().getGameTime())) return;
            context.reply(new ContainerMenuInfoPacket(packet.menuId, packet.requestId,
                ContainerMenuInfos.resolve(player)));
        });
    }
}
