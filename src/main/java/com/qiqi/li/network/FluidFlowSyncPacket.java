package com.qiqi.li.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.qiqi.li.living.domain.water.FluidFlowClientCache;

/**
 * S2C 容器流体快照同步包（Q5 渲染轨，2026-10-03）。
 *
 * <p>下发容器级 flow 表到客户端水流渲染（{@code AbstractContainerScreenMixin}）。
 * 不依赖活水桶物品 —— 纯源容器（无任何活物品）的水也能渲染。</p>
 *
 * <p>流体类型以<b>调色板</b>传输（包内去重，cell 存下标）—— 只用 {@code Registry}
 * 接口的 {@code getKey}/{@code get}，不依赖 int id。cell 值 =
 * {@code int[]{level, fromSlot, fluidIndex}}。</p>
 *
 * <p>编码格式：</p>
 * <pre>
 *   containerKey (string)
 *   width (varint)          — 容器格子宽度，客户端算方向用
 *   paletteSize (varint)
 *   palette: fluidType key (string) × paletteSize
 *   cellCount (varint)
 *   for each cell:
 *     slot (varint)
 *     level (varint)
 *     fromSlot (varint)     — 源为 -1，编码 +1（varint 无负数）
 *     fluidIndex (varint)
 * </pre>
 */
public record FluidFlowSyncPacket(
    String containerKey,
    int width,
    List<String> palette,
    Map<Integer, int[]> cells,
    FluidFlowClientCache.RenderTarget target
) implements CustomPacketPayload {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("living_item", "fluid_flow_sync");
    public static final CustomPacketPayload.Type<FluidFlowSyncPacket> TYPE =
        new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, FluidFlowSyncPacket> STREAM_CODEC =
        StreamCodec.of(FluidFlowSyncPacket::encode, FluidFlowSyncPacket::decode);

    @Override
    public CustomPacketPayload.Type<FluidFlowSyncPacket> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, FluidFlowSyncPacket pkt) {
        buf.writeUtf(pkt.containerKey);
        buf.writeVarInt(pkt.width);
        buf.writeVarInt(pkt.palette.size());
        for (String key : pkt.palette) {
            buf.writeUtf(key);
        }
        buf.writeVarInt(pkt.cells.size());
        for (var entry : pkt.cells.entrySet()) {
            int[] cell = entry.getValue();
            buf.writeVarInt(entry.getKey());
            buf.writeVarInt(cell[0]);
            buf.writeVarInt(cell[1] + 1);   // fromSlot + 1，-1 → 0（varint 无负数）
            buf.writeVarInt(cell[2]);
        }
        buf.writeVarInt(pkt.target().ordinal());
    }

    private static FluidFlowSyncPacket decode(FriendlyByteBuf buf) {
        String containerKey = buf.readUtf();
        int width = buf.readVarInt();
        int paletteSize = buf.readVarInt();
        List<String> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(buf.readUtf());
        }
        int count = buf.readVarInt();
        Map<Integer, int[]> cells = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            int slot = buf.readVarInt();
            int level = buf.readVarInt();
            int fromSlot = buf.readVarInt() - 1;
            int fluidIndex = buf.readVarInt();
            cells.put(slot, new int[]{level, fromSlot, fluidIndex});
        }
        var targets = FluidFlowClientCache.RenderTarget.values();
        int ordinal = buf.readVarInt();
        FluidFlowClientCache.RenderTarget target =
            ordinal >= 0 && ordinal < targets.length ? targets[ordinal] : FluidFlowClientCache.RenderTarget.BLOCK;
        return new FluidFlowSyncPacket(containerKey, width, palette, cells, target);
    }

    public static void handle(FluidFlowSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            List<FluidType> fluids = new ArrayList<>(packet.palette().size());
            for (String key : packet.palette()) {
                ResourceLocation rl = ResourceLocation.tryParse(key);
                FluidType type = rl == null ? null : NeoForgeRegistries.FLUID_TYPES.get(rl);
                fluids.add(type);
            }
            FluidFlowClientCache.update(packet.target(),
                FluidFlowClientCache.copyOf(packet.width(), fluids, packet.cells()));
        });
    }
}
