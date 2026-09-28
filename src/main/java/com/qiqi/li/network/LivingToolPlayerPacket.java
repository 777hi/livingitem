package com.qiqi.li.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.domain.tools.LivingToolPlayerClientCache;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：<b>附近玩家背包里的「无记忆活工具」清单</b>（联机可见性 · 最小版）。
 *
 * <p>用途：让<b>别人也能看到你背后的那个待机环</b>。原来渲染器只读
 * {@code mc.player.getInventory()}，所以自己的工具只有自己看得见 ——
 * 这个包把"谁身上有哪些工具"同步出去。</p>
 *
 * <p>⭐ <b>为什么包里只有 UUID + 工具列表</b>：玩家的<b>位置与朝向</b>走原版实体同步，
 * 客户端渲染时直接读 {@code Player}（带帧间插值）即可，<b>不需要进包</b>。</p>
 *
 * <p>⚠️ <b>最小版不含挖掘环</b>：那需要"他正在挖哪一格"，而 {@code LivingToolAssistState}
 * 是各机器<b>本地</b>记录的（它自己注释里写明"不需要网络同步"）⇒ 要额外广播才能同步。</p>
 *
 * @param dimension 所在维度，供客户端做残留保护（换维度 / 换存档时不串台）
 * @param entries   本包覆盖的全部条目；客户端<b>整体替换</b>缓存
 */
public record LivingToolPlayerPacket(
    ResourceLocation dimension,
    List<Entry> entries
) implements CustomPacketPayload {

    /** 一个玩家 + 他背包里的无记忆活工具。 */
    public record Entry(UUID playerId, List<ItemStack> tools) {
    }

    public static final Type<LivingToolPlayerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "living_tool_player"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingToolPlayerPacket> STREAM_CODEC =
        StreamCodec.of(LivingToolPlayerPacket::encode, LivingToolPlayerPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── 编解码 ──────────────────────────────────────────────

    private static void encode(RegistryFriendlyByteBuf buf, LivingToolPlayerPacket pkt) {
        buf.writeResourceLocation(pkt.dimension);
        buf.writeVarInt(pkt.entries.size());
        for (Entry entry : pkt.entries) {
            buf.writeUUID(entry.playerId());
            buf.writeVarInt(entry.tools().size());
            for (ItemStack stack : entry.tools()) {
                ItemStack.STREAM_CODEC.encode(buf, stack);
            }
        }
    }

    private static LivingToolPlayerPacket decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID playerId = buf.readUUID();
            int toolCount = buf.readVarInt();
            List<ItemStack> tools = new ArrayList<>(toolCount);
            for (int j = 0; j < toolCount; j++) {
                tools.add(ItemStack.STREAM_CODEC.decode(buf));
            }
            entries.add(new Entry(playerId, tools));
        }
        return new LivingToolPlayerPacket(dimension, entries);
    }

    // ── 客户端处理 ──────────────────────────────────────────

    public static void handle(LivingToolPlayerPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            LivingToolPlayerClientCache.update(packet.dimension(), packet.entries()));
    }
}
