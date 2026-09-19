package com.qiqi.li.network;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.domain.tools.LivingToolHostClientCache;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：「哪些容器里有活工具」同步包（{@code K2}）。
 *
 * <h3>为什么需要它（现有 {@link LivingItemSyncPacket} 不行）</h3>
 * <ul>
 *   <li>它是 <b>tooltip 通道</b> —— 只发给<b>正在开该容器 GUI 的玩家</b>
 *       （{@code isViewingContainer}），而世界渲染要的是"路过就能看见"。</li>
 *   <li>它<b>不含 ItemStack</b> —— 客户端既画不出记忆射线、也渲染不了悬浮模型。</li>
 * </ul>
 *
 * <h3>同步什么</h3>
 * <ul>
 *   <li>{@code BlockPos} —— 容器位置（大箱子取 {@code getBlockPos()} 第一格）</li>
 *   <li>{@code ItemStack} —— 活工具本体。它<b>一次满足三个需求</b>：
 *       <br>① 画记忆射线（{@code LIVING_TOOL_MEMORY} 已 {@code networkSynchronized}）
 *       <br>② 渲染悬浮模型（物品本体）
 *       <br>③ 未来的动画状态（做成 network-only 组件同样随它走）</li>
 * </ul>
 *
 * <p><b>安全</b>：只同步 {@code LivingToolRecorder#isLivingTool} 筛过的活工具，
 * <b>不泄露容器内其它物品</b>。活工具 {@code maxStackSize = 1}（不堆叠）+ 只在近处
 * ⇒ 条目是个位数。</p>
 *
 * <h3>发送策略（见 {@code LivingToolHostSync}）</h3>
 * 按距离定向（R = 32，<b>必须与原版破坏裂纹广播半径一致</b>），
 * <b>只在内容变化时才发</b>（无节流 —— 内容变化频率本就极低）。
 *
 * @param dimension 所在维度，供客户端做残留保护（换维度 / 换存档时旧数据不串台）
 * @param entries   本包覆盖的全部条目；客户端<b>整体替换</b>缓存
 */
public record LivingToolHostPacket(
    ResourceLocation dimension,
    List<Entry> entries
) implements CustomPacketPayload {

    /** 一个宿主容器 + 其中的活工具。 */
    public record Entry(BlockPos pos, List<ToolRay> tools) {
    }

    /**
     * 一把活工具 + 它两条记忆<b>当前有没有打中</b>（{@code L48}）。
     *
     * <p>⭐ 只同步一个<b>布尔</b>，<b>不同步目标坐标</b> —— 客户端画的是
     * <b>记忆射线本身</b>（{@code origin → origin + offset}，<b>恒定</b>），
     * 压根不需要知道"打到哪一格"。</p>
     *
     * <p>曾走过弯路：同步 {@code BlockPos} 并把终点画到目标方块中心，
     * 结果<b>射线会跟着目标跳</b>（挖完一格就跳下一格），背离了 {@code L19}
     * "把记忆画出来、让玩家看出偏没偏"的初衷（2026-09-19 用户指出）。</p>
     *
     * <p>布尔由<b>服务端</b> {@code LivingToolReplay#resolveDigTarget} /
     * {@code resolveUseTarget} 判定 —— <b>客户端不自算</b>：命中判定有
     * 「宿主黑名单 / 流体 / 形状求交」三层，复刻必然走偏。</p>
     */
    public record ToolRay(ItemStack stack, boolean digLanded, boolean useLanded) {
    }

    public static final Type<LivingToolHostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "living_tool_host"));

    public static final StreamCodec<FriendlyByteBuf, LivingToolHostPacket> STREAM_CODEC =
        StreamCodec.of(LivingToolHostPacket::encode, LivingToolHostPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── 编码 ────────────────────────────────────────────────

    private static void encode(FriendlyByteBuf buf, LivingToolHostPacket pkt) {
        buf.writeResourceLocation(pkt.dimension);
        buf.writeVarInt(pkt.entries.size());
        for (Entry entry : pkt.entries) {
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.tools().size());
            for (ToolRay tool : entry.tools()) {
                ItemStack.STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, tool.stack());
                buf.writeBoolean(tool.digLanded());
                buf.writeBoolean(tool.useLanded());
            }
        }
    }

    private static LivingToolHostPacket decode(FriendlyByteBuf buf) {
        RegistryFriendlyByteBuf reg = (RegistryFriendlyByteBuf) buf;
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            int toolCount = buf.readVarInt();
            List<ToolRay> tools = new ArrayList<>(toolCount);
            for (int j = 0; j < toolCount; j++) {
                ItemStack stack = ItemStack.STREAM_CODEC.decode(reg);
                tools.add(new ToolRay(stack, buf.readBoolean(), buf.readBoolean()));
            }
            entries.add(new Entry(pos, tools));
        }
        return new LivingToolHostPacket(dimension, entries);
    }

    // ── 客户端处理 ──────────────────────────────────────────

    public static void handle(LivingToolHostPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            LivingToolHostClientCache.update(packet.dimension(), packet.entries()));
    }
}
