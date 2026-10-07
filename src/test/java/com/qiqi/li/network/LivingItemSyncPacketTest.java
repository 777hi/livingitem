package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import com.qiqi.li.living.domain.furnace.TransformData;
import com.qiqi.li.living.domain.hopper.ResolvedSlotData;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData.FurnaceRuntime;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData.HopperRuntime;

/**
 * {@link LivingItemSyncPacket} 编解码往返测试（2026-10-08，档 2 步骤 B）。
 *
 * <p><b>为什么先有它</b>：它给「runtime 片段泛化」（档 2 步骤 C）当<b>安全网</b> ——
 * 改完 C 之后，本测试<b>不改一行仍须全绿</b>，即为「线上字节格式语义未变」的机械证明。</p>
 *
 * <p>覆盖：三组数据各自的字段往返、空数据、多槽混合、以及 key 的两种形态
 * （普通容器键 / {@code player_<uuid>}）。</p>
 */
class LivingItemSyncPacketTest {

    // ── 工具 ────────────────────────────────────────────────

    /** 编码 → 解码 往返（用真实 bytebuf，不 mock）。 */
    private static LivingItemSyncPacket roundTrip(LivingItemSyncPacket pkt) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        LivingItemSyncPacket.STREAM_CODEC.encode(buf, pkt);
        return LivingItemSyncPacket.STREAM_CODEC.decode(buf);
    }

    private static LivingWaxedGeneratorData sampleGenerator() {
        return new LivingWaxedGeneratorData(
            20, 3, 750, 7, 1234, LivingWaxedGeneratorData.FORM_CHISELED,
            456_000L, 789_000L,
            List.of(new LivingWaxedGeneratorData.DomainSnapshot(
                20, 3, 5, 1.5, List.of(0, 6, 13), List.of(1, 2, 3))),
            1.25, 0.75, 2, List.of(100L, 200L)
        );
    }

    private static ResolvedSlotData sampleSlot() {
        return new ResolvedSlotData(4, 13, 22, 27, 9);
    }

    private static TransformData sampleTransform() {
        return new TransformData("minecraft:raw_iron", "minecraft:iron_ingot",
            "minecraft:raw_iron", 1, "minecraft:iron_ingot", 2, 200);
    }

    // ── 三组各自往返 ─────────────────────────────────────────

    @Test
    @DisplayName("仅发电机：13 个遥测字段往返一致")
    void generator_only_roundTrips() {
        var data = LivingItemRuntimeData.forGenerator(sampleGenerator());
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of(3, data)));

        var g = got.slotData().get(3).generatorTelemetry();
        assertEquals(sampleGenerator(), g, "发电机遥测往返后应逐字段相等");
        assertNull(got.slotData().get(3).hopper(), "未写的段应为 null");
        assertNull(got.slotData().get(3).furnace(), "未写的段应为 null");
    }

    @Test
    @DisplayName("仅漏斗：cooldown + slotInfo 往返一致")
    void hopper_only_roundTrips() {
        var data = LivingItemRuntimeData.forHopper(42, sampleSlot());
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of(7, data)));

        var h = got.slotData().get(7).hopper();
        assertEquals(42, h.cooldown());
        assertEquals(sampleSlot(), h.slotInfo());
    }

    @Test
    @DisplayName("漏斗无槽位信息（slotInfo = null）：标志位往返正确")
    void hopper_withoutSlotInfo_roundTrips() {
        var data = LivingItemRuntimeData.forHopper(11, null);
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of(1, data)));

        assertEquals(11, got.slotData().get(1).hopper().cooldown());
        assertNull(got.slotData().get(1).hopper().slotInfo(),
            "slotInfo 为 null 必须能往返（布尔标志位不能丢）");
    }

    @Test
    @DisplayName("仅熔炉：progress/total/burnTime + transform 往返一致")
    void furnace_only_roundTrips() {
        var data = LivingItemRuntimeData.forFurnace(37, 200, 1600, sampleTransform());
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of(5, data)));

        var f = got.slotData().get(5).furnace();
        assertEquals(37, f.progress());
        assertEquals(200, f.total());
        assertEquals(1600, f.burnTime());
        assertEquals(sampleTransform(), f.transform());
    }

    @Test
    @DisplayName("熔炉无配方缓存（transform = null）：标志位往返正确")
    void furnace_withoutTransform_roundTrips() {
        var data = LivingItemRuntimeData.forFurnace(1, 0, 0, null);
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of(2, data)));

        assertNull(got.slotData().get(2).furnace().transform());
    }

    // ── 边界 ────────────────────────────────────────────────

    @Test
    @DisplayName("空快照（EMPTY）：三组全 null，可往返")
    void emptyData_roundTrips() {
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0",
            Map.of(0, LivingItemRuntimeData.EMPTY)));

        var d = got.slotData().get(0);
        assertFalse(d.isGenerator());
        assertFalse(d.isHopper());
        assertFalse(d.isFurnace());
    }

    @Test
    @DisplayName("多槽混合：三个槽各自承担不同类型的段，互不串台")
    void multiSlot_mixedSegments_doNotInterfere() {
        var pkt = new LivingItemSyncPacket("chest_1_2_3", Map.of(
            0, LivingItemRuntimeData.forGenerator(sampleGenerator()),
            5, LivingItemRuntimeData.forHopper(9, sampleSlot()),
            26, LivingItemRuntimeData.forFurnace(3, 100, 800, sampleTransform())
        ));
        var got = roundTrip(pkt);

        assertEquals(3, got.slotData().size(), "槽数应保持");
        assertTrue(got.slotData().get(0).isGenerator());
        assertTrue(got.slotData().get(5).isHopper());
        assertTrue(got.slotData().get(26).isFurnace());
        assertEquals(sampleGenerator(), got.slotData().get(0).generatorTelemetry());
        assertEquals(9, got.slotData().get(5).hopper().cooldown());
        assertEquals(3, got.slotData().get(26).furnace().progress());
    }

    @Test
    @DisplayName("容器键两种形态（普通 / player_<uuid>）都能往返")
    void containerKey_bothForms_survive() {
        String uuidKey = "player_550e8400-e29b-41d4-a716-446655440000";
        var data = LivingItemRuntimeData.forHopper(1, null);

        assertEquals("chest_0_64_0",
            roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of(0, data))).containerKey());
        assertEquals(uuidKey,
            roundTrip(new LivingItemSyncPacket(uuidKey, Map.of(0, data))).containerKey(),
            "player_ 前缀键必须原样往返（客户端据此分流）");
    }

    @Test
    @DisplayName("空槽集合：编解码不炸")
    void emptySlotMap_roundTrips() {
        var got = roundTrip(new LivingItemSyncPacket("chest_0_64_0", Map.of()));
        assertTrue(got.slotData().isEmpty());
    }

    // ── 段内子记录的哨兵值（防编解码漏字段）─────────────────

    @Test
    @DisplayName("漏斗子记录哨兵：cooldown=0 与 slotInfo=EMPTY 的区分")
    void hopper_sentinel_distinguishesNullOrPresent() {
        var withEmptySlot = LivingItemRuntimeData.forHopper(0, ResolvedSlotData.EMPTY);
        var got = roundTrip(new LivingItemSyncPacket("k", Map.of(0, withEmptySlot)));

        assertEquals(ResolvedSlotData.EMPTY, got.slotData().get(0).hopper().slotInfo(),
            "EMPTY（非 null）必须在往返后仍是 EMPTY，不能被当成 null");
    }

    @Test
    @DisplayName("发电机空域列表与哨兵值往返")
    void generator_emptyCollections_roundTrip() {
        var g = new LivingWaxedGeneratorData(0, 0, 0, 0, 0, 0, 0L, 0L,
            List.of(), 1.0, 0.0, 0, List.of());
        var got = roundTrip(new LivingItemSyncPacket("k",
            Map.of(0, LivingItemRuntimeData.forGenerator(g))));

        assertEquals(g, got.slotData().get(0).generatorTelemetry());
    }

    @Test
    @DisplayName("HopperRuntime / FurnaceRuntime 哨兵常量不被编解码改写")
    void runtimeSentinels_survive() {
        var h = roundTrip(new LivingItemSyncPacket("k",
            Map.of(0, new LivingItemRuntimeData(null, HopperRuntime.EMPTY, null))));
        assertEquals(HopperRuntime.EMPTY, h.slotData().get(0).hopper());

        var f = roundTrip(new LivingItemSyncPacket("k",
            Map.of(0, new LivingItemRuntimeData(null, null, FurnaceRuntime.EMPTY))));
        assertEquals(FurnaceRuntime.EMPTY, f.slotData().get(0).furnace());
    }
}
