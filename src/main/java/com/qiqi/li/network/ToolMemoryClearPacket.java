package com.qiqi.li.network;

import com.qiqi.li.LivingItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 清除活工具 / 活武器记忆（C2S）—— 手持时<b>左键空气</b>由客户端发起。
 *
 * <p>为什么需要这个包：{@code PlayerInteractEvent.LeftClickEmpty} <b>只在客户端触发</b>
 * （NeoForge 源码注释原文：<i>"The server is not aware of when the client left clicks empty space,
 * you will need to tell the server yourself."</i>）。
 * 右键空气则可以走服务端的 {@code RightClickItem} 事件，不需要包。</p>
 *
 * <p>⭐ <b>为什么是三态整数而不是 boolean</b>：原先只有 {@code dig}（挖掘 / 交互两种），
 * 活武器加入后需要第三种（攻击）⇒ boolean 不够用。
 * 用 {@code int} 而非枚举，是为了让 {@code StreamCodec} 保持最简单（{@code VAR_INT}）。</p>
 *
 * @param kind 见 {@link #KIND_DIG} / {@link #KIND_USE} / {@link #KIND_ATTACK}
 */
public record ToolMemoryClearPacket(int kind) implements CustomPacketPayload {

    /** 清除<b>挖掘</b>记忆（左键）。 */
    public static final int KIND_DIG = 0;

    /** 清除<b>交互</b>记忆（右键）。 */
    public static final int KIND_USE = 1;

    /** 清除<b>攻击</b>记忆（活武器 —— 这一刀挥空了，没打到怪）。 */
    public static final int KIND_ATTACK = 2;

    public static final Type<ToolMemoryClearPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "tool_memory_clear"));

    public static final StreamCodec<FriendlyByteBuf, ToolMemoryClearPacket> STREAM_CODEC = StreamCodec.of(
        (buf, pkt) -> buf.writeVarInt(pkt.kind()),
        buf -> new ToolMemoryClearPacket(buf.readVarInt())
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
