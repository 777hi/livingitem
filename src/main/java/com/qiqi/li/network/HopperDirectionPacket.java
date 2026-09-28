package com.qiqi.li.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.qiqi.li.LivingItem;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 活漏斗方向配置网络包（v2 版本）。
 *
 * 功能：
 * 将客户端的活漏斗传输方向修改请求发送到服务端，
 * 由服务端更新活漏斗的 NBT 数据并同步回客户端。
 *
 * 数据格式：
 * 使用 SlotMapping 的 NBT 格式存储方向数据：
 * - src_x, src_y：源方向坐标
 * - tgt_x, tgt_y：目标方向坐标
 * - symbol：显示符号（如 "↑→↓"）
 * - name：显示名称（如 "上传下"）
 *
 * 通信流程：
 *   客户端 LivingItemInputHandler → PacketDistributor.sendToServer()
 * → 服务端 ServerPacketHandler.handleHopperDirection()
 * → 更新光标物品 NBT → 同步到客户端
 *
 * 安全性：
 * - 服务端验证玩家是否持有活漏斗
 * - 服务端验证光标物品是否为活物品
 * - 只更新光标物品，不信任客户端传来的其他数据
 */
public record HopperDirectionPacket(
    CompoundTag mappingData
) implements CustomPacketPayload {

    /** 包类型标识符 */
    public static final Type<HopperDirectionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "hopper_direction_v2"));

    /** 自定义 StreamCodec 实现（处理 NBT 可能为 null 的情况） */
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

    /** 获取包类型 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 处理接收到的包（在服务端执行）。
     *
     * 通过 IPayloadContext.enqueueWork() 确保在主线程执行，
     * 避免线程安全问题。
     *
     * @param payload 接收到的包数据
     * @param context 包上下文（包含玩家信息）
     */
    public static void handle(HopperDirectionPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                ServerPacketHandler.handleHopperDirection(serverPlayer, payload);
            }
        });
    }
}