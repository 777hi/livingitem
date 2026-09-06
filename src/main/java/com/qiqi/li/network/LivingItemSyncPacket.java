package com.qiqi.li.network;

import com.qiqi.li.living.domain.furnace.TransformData;
import com.qiqi.li.living.domain.hopper.ResolvedSlotData;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData;
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData.FurnaceRuntime;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData.HopperRuntime;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

/**
 * S2C 活物品运行时数据同步包。
 *
 * <p>将服务端 {@link com.qiqi.li.living.domain.runtime.ContainerRuntimeCache} 中的
 * 运行时数据下发到客户端，用于 Tooltip 渲染。数据不经过 DataComponent，
 * 因此不影响物品堆叠。</p>
 *
 * <p>编码格式：</p>
 * <pre>
 *   containerKey (string)
 *   slotCount (varint)
 *   for each slot:
 *     slotIndex (varint)
 *     flags (byte) — bit0=generator, bit1=hopper, bit2=furnace
 *     [generator fields if flags & 1]
 *     [hopper fields if flags & 2]
 *     [furnace fields if flags & 4]
 * </pre>
 */
public record LivingItemSyncPacket(
    String containerKey,
    Map<Integer, LivingItemRuntimeData> slotData
) implements CustomPacketPayload {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("living_item", "living_item_sync");
    public static final CustomPacketPayload.Type<LivingItemSyncPacket> TYPE =
        new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, LivingItemSyncPacket> STREAM_CODEC =
        StreamCodec.of(LivingItemSyncPacket::encode, LivingItemSyncPacket::decode);

    @Override
    public CustomPacketPayload.Type<LivingItemSyncPacket> type() {
        return TYPE;
    }

    // ── 编码 ────────────────────────────────────────────────

    private static void encode(FriendlyByteBuf buf, LivingItemSyncPacket pkt) {
        buf.writeUtf(pkt.containerKey);
        buf.writeVarInt(pkt.slotData.size());
        for (var entry : pkt.slotData.entrySet()) {
            buf.writeVarInt(entry.getKey());
            encodeRuntimeData(buf, entry.getValue());
        }
    }

    private static void encodeRuntimeData(FriendlyByteBuf buf, LivingItemRuntimeData data) {
        byte flags = 0;
        if (data.isGenerator()) flags |= 1;
        if (data.isHopper())   flags |= 2;
        if (data.isFurnace())  flags |= 4;
        buf.writeByte(flags);

        if (data.isGenerator()) {
            encodeGenerator(buf, data.generatorTelemetry());
        }
        if (data.isHopper()) {
            encodeHopper(buf, data.hopper());
        }
        if (data.isFurnace()) {
            encodeFurnace(buf, data.furnace());
        }
    }

    private static void encodeGenerator(FriendlyByteBuf buf, LivingWaxedGeneratorData g) {
        RegistryFriendlyByteBuf regBuf = (RegistryFriendlyByteBuf) buf;
        ByteBufCodecs.VAR_INT.encode(buf, g.detectedPeriod());
        ByteBufCodecs.VAR_INT.encode(buf, g.phaseCount());
        ByteBufCodecs.VAR_INT.encode(buf, g.unlockPermille());
        ByteBufCodecs.VAR_INT.encode(buf, g.lastDelta());
        ByteBufCodecs.VAR_INT.encode(buf, g.effDeltaSumPermille());
        ByteBufCodecs.VAR_INT.encode(buf, g.coilForm());
        ByteBufCodecs.VAR_LONG.encode(buf, g.emaPowerMilliFe());
        ByteBufCodecs.VAR_LONG.encode(buf, g.levelEmaPowerMilliFe());
        com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DomainSnapshot.STREAM_CODEC
            .apply(ByteBufCodecs.list()).encode(regBuf, g.domains());
        ByteBufCodecs.DOUBLE.encode(buf, g.resonanceGain());
        ByteBufCodecs.DOUBLE.encode(buf, g.resonanceBalance());
        ByteBufCodecs.VAR_INT.encode(buf, g.activeLevels());
        ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).encode(buf, g.levelPowerMilliFe());
    }

    private static void encodeHopper(FriendlyByteBuf buf, HopperRuntime h) {
        buf.writeVarInt(h.cooldown());
        ResolvedSlotData slot = h.slotInfo();
        if (slot != null) {
            buf.writeBoolean(true);
            encodeResolvedSlotData(buf, slot);
        } else {
            buf.writeBoolean(false);
        }
    }

    private static void encodeFurnace(FriendlyByteBuf buf, FurnaceRuntime f) {
        buf.writeVarInt(f.progress());
        buf.writeVarInt(f.total());
        buf.writeVarInt(f.burnTime());
        TransformData t = f.transform();
        if (t != null) {
            buf.writeBoolean(true);
            encodeTransformData(buf, t);
        } else {
            buf.writeBoolean(false);
        }
    }

    private static void encodeResolvedSlotData(FriendlyByteBuf buf, ResolvedSlotData s) {
        buf.writeInt(s.hostSlot());
        buf.writeInt(s.sourceSlot());
        buf.writeInt(s.targetSlot());
        buf.writeInt(s.containerSize());
        buf.writeInt(s.containerWidth());
    }

    private static void encodeTransformData(FriendlyByteBuf buf, TransformData t) {
        buf.writeUtf(t.inputItem());
        buf.writeUtf(t.outputItem());
        buf.writeUtf(t.cachedInput());
        buf.writeVarInt(t.cachedResult());
        buf.writeUtf(t.cachedOutput());
        buf.writeVarInt(t.cachedOutputCount());
        buf.writeVarInt(t.cookingTime());
    }

    // ── 解码 ────────────────────────────────────────────────

    private static LivingItemSyncPacket decode(FriendlyByteBuf buf) {
        String containerKey = buf.readUtf();
        int slotCount = buf.readVarInt();
        Map<Integer, LivingItemRuntimeData> slotData = new HashMap<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            int slot = buf.readVarInt();
            slotData.put(slot, decodeRuntimeData(buf));
        }
        return new LivingItemSyncPacket(containerKey, slotData);
    }

    private static LivingItemRuntimeData decodeRuntimeData(FriendlyByteBuf buf) {
        byte flags = buf.readByte();

        LivingWaxedGeneratorData generator = null;
        HopperRuntime hopper = null;
        FurnaceRuntime furnace = null;

        if ((flags & 1) != 0) {
            generator = decodeGenerator(buf);
        }
        if ((flags & 2) != 0) {
            hopper = decodeHopper(buf);
        }
        if ((flags & 4) != 0) {
            furnace = decodeFurnace(buf);
        }

        return new LivingItemRuntimeData(generator, hopper, furnace);
    }

    private static LivingWaxedGeneratorData decodeGenerator(FriendlyByteBuf buf) {
        RegistryFriendlyByteBuf regBuf = (RegistryFriendlyByteBuf) buf;
        int dp = ByteBufCodecs.VAR_INT.decode(buf);
        int pc = ByteBufCodecs.VAR_INT.decode(buf);
        int up = ByteBufCodecs.VAR_INT.decode(buf);
        int ld = ByteBufCodecs.VAR_INT.decode(buf);
        int es = ByteBufCodecs.VAR_INT.decode(buf);
        int cf = ByteBufCodecs.VAR_INT.decode(buf);
        long epf = ByteBufCodecs.VAR_LONG.decode(buf);
        long cepf = ByteBufCodecs.VAR_LONG.decode(buf);
        var ds = com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DomainSnapshot.STREAM_CODEC
            .apply(ByteBufCodecs.list()).decode(regBuf);
        double rg = ByteBufCodecs.DOUBLE.decode(buf);
        double rb = ByteBufCodecs.DOUBLE.decode(buf);
        int av = ByteBufCodecs.VAR_INT.decode(buf);
        var vp = ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).decode(buf);
        return new LivingWaxedGeneratorData(dp, pc, up, ld, es, cf, epf, cepf, ds, rg, rb, av, vp);
    }

    private static HopperRuntime decodeHopper(FriendlyByteBuf buf) {
        int cooldown = buf.readVarInt();
        ResolvedSlotData slot = buf.readBoolean() ? decodeResolvedSlotData(buf) : null;
        return new HopperRuntime(cooldown, slot);
    }

    private static FurnaceRuntime decodeFurnace(FriendlyByteBuf buf) {
        int progress = buf.readVarInt();
        int total = buf.readVarInt();
        int burnTime = buf.readVarInt();
        TransformData transform = buf.readBoolean() ? decodeTransformData(buf) : null;
        return new FurnaceRuntime(progress, total, burnTime, transform);
    }

    private static ResolvedSlotData decodeResolvedSlotData(FriendlyByteBuf buf) {
        return new ResolvedSlotData(
            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()
        );
    }

    private static TransformData decodeTransformData(FriendlyByteBuf buf) {
        return new TransformData(
            buf.readUtf(), buf.readUtf(), buf.readUtf(),
            buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt()
        );
    }

    // ── 客户端处理 ───────────────────────────────────────────

    public static void handle(LivingItemSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            LivingItemClientCache.update(packet.containerKey, packet.slotData);
        });
    }
}