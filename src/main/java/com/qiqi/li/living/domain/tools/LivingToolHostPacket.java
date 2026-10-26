package com.qiqi.li.living.domain.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.qiqi.li.living.api.LivingMod;
import com.qiqi.li.living.container.HostRef;

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
 *   <li>{@link HostRef} —— 宿主<b>是谁 / 在哪</b>（方块容器 → 位置；物品形态容器 →
 *       它借住的宿主：玩家 / 实体 / 方块）</li>
 *   <li>{@code ItemStack} —— 活工具本体。它<b>一次满足三个需求</b>：
 *       <br>① 画记忆射线（{@code LIVING_TOOL_MEMORY} 已 {@code networkSynchronized}）
 *       <br>② 渲染悬浮模型（物品本体）
 *       <br>③ 未来的动画状态（做成 network-only 组件同样随它走）</li>
 * </ul>
 *
 * <h3>为什么宿主键有三种（2026-10-26，活背包 6.1）</h3>
 * <p>原来只有 {@code BlockPos} —— 物品形态容器（活背包）没有方块位置，于是整条通道
 * 对它不可达，背包里的活工具在世界里<b>完全不可见</b>。而它的内容客户端也读不到：
 * 精妙的内容在服务端 {@code BackpackStorage}（SavedData），客户端只有一份按需填充的副本
 * ⇒ <b>没有"客户端自己算"这条路</b>，只能由服务端下发。</p>
 *
 * <p>⇒ 键扩成三种，位置由服务端给（物品形态的锚点来自
 * {@code ContainerContext#getHostAnchor()}）。<b>仍然只有这一条通道</b> ——
 * 另开一条"物品形态专用包"就是同一件事的两份答案。</p>
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
    public record Entry(HostRef host, List<ToolRay> tools) {
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
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingMod.ID, "living_tool_host"));

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
            buf.writeByte(entry.host().kind().ordinal());
            if (entry.host().kind() == HostRef.Kind.BLOCK) {
                buf.writeBlockPos(entry.host().pos());
            } else {
                buf.writeUUID(entry.host().entityId());
            }
            // +1 是为了让 -1（方块 / 不知道）也能走 VarInt（负数 VarInt 要 5 字节）
            buf.writeVarInt(entry.host().hostSlot() + 1);
            buf.writeVarInt(entry.tools().size());
            for (ToolRay tool : entry.tools()) {
                ItemStack.STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, tool.stack());
                buf.writeBoolean(tool.digLanded());
                buf.writeBoolean(tool.useLanded());
            }
        }
    }

    /** 解码容量上限（防御脏包：负数 / 超大 varint 不许直接进 ArrayList 构造 ⇒ 不许 OOM）。 */
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_TOOLS = 256;

    private static int readClamped(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        return n < 0 ? 0 : Math.min(n, max);
    }

    private static LivingToolHostPacket decode(FriendlyByteBuf buf) {
        RegistryFriendlyByteBuf reg = (RegistryFriendlyByteBuf) buf;
        ResourceLocation dimension = buf.readResourceLocation();
        int count = readClamped(buf, MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 脏包的越界 kind 无法继续解析（后续字节的布局取决于 kind）⇒ 只能整体放弃，
            // 返回空包让客户端整体替换缓存 —— 不许 AIOOBE 炸网络线程
            int ordinal = buf.readUnsignedByte();
            HostRef.Kind[] kinds = HostRef.Kind.values();
            if (ordinal >= kinds.length) {
                return new LivingToolHostPacket(dimension, List.of());
            }
            HostRef.Kind kind = kinds[ordinal];
            BlockPos pos = null;
            UUID entityId = null;
            switch (kind) {
                case BLOCK -> pos = buf.readBlockPos();
                case PLAYER, ENTITY -> entityId = buf.readUUID();
            }
            HostRef host = new HostRef(kind, pos, entityId, buf.readVarInt() - 1);
            int toolCount = readClamped(buf, MAX_TOOLS);
            List<ToolRay> tools = new ArrayList<>(toolCount);
            for (int j = 0; j < toolCount; j++) {
                ItemStack stack = ItemStack.STREAM_CODEC.decode(reg);
                tools.add(new ToolRay(stack, buf.readBoolean(), buf.readBoolean()));
            }
            entries.add(new Entry(host, tools));
        }
        return new LivingToolHostPacket(dimension, entries);
    }

    // ── 客户端处理 ──────────────────────────────────────────

    public static void handle(LivingToolHostPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
            LivingToolHostClientCache.update(packet.dimension(), packet.entries()));
    }
}
