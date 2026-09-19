package com.qiqi.li.network;

import com.qiqi.li.LivingItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 清除活工具记忆（C2S）—— 手持活工具<b>左键空气</b>时由客户端发起。
 *
 * <p>为什么需要这个包：{@code PlayerInteractEvent.LeftClickEmpty} <b>只在客户端触发</b>
 * （NeoForge 源码注释原文：<i>"The server is not aware of when the client left clicks empty space,
 * you will need to tell the server yourself."</i>）。
 * 右键空气则可以走服务端的 {@code RightClickItem} 事件，不需要包。</p>
 *
 * @param dig {@code true} = 清除<b>挖掘记忆</b>（左键）；{@code false} = 清除<b>交互记忆</b>（右键）
 */
public record ToolMemoryClearPacket(boolean dig) implements CustomPacketPayload {

    public static final Type<ToolMemoryClearPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "tool_memory_clear"));

    public static final StreamCodec<FriendlyByteBuf, ToolMemoryClearPacket> STREAM_CODEC = StreamCodec.of(
        (buf, pkt) -> buf.writeBoolean(pkt.dig()),
        buf -> new ToolMemoryClearPacket(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolMemoryClearPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ServerPacketHandler.handleToolMemoryClear(serverPlayer, payload);
            }
        });
    }
}
