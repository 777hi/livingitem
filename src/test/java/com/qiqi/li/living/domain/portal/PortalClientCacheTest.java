package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** 门面快照的菜单隔离、逻辑映射和不可变包载荷守卫。 */
class PortalClientCacheTest {

    @AfterEach
    void clean() {
        PortalClientCache.clear();
    }

    private static PortalSnapshotPacket packet(int menu, String key, Set<Integer> cells, Map<Integer, Integer> slots) {
        return new PortalSnapshotPacket(menu, key, 9, 27, cells, slots);
    }

    @Test
    void packetAndSnapshotCopyInputCollectionsAndExposeOnlyImmutableViews() {
        Set<Integer> cells = new HashSet<>(Set.of(4));
        Map<Integer, Integer> mapping = new HashMap<>(Map.of(40, 4));
        PortalSnapshotPacket packet = packet(1, "one", cells, mapping);
        PortalClientCache.update(1, packet);
        cells.clear();
        mapping.put(40, 6);
        assertEquals(Set.of(4), packet.cells());
        assertEquals(Map.of(40, 4), packet.menuSlots());
        assertTrue(PortalClientCache.contains(1, 40));
        assertFalse(PortalClientCache.contains(1, 4), "菜单槽号不能直接当逻辑槽");
        var snapshots = PortalClientCache.snapshots(1);
        var snapshot = snapshots.getFirst();
        assertThrows(UnsupportedOperationException.class, () -> packet.cells().add(7));
        assertThrows(UnsupportedOperationException.class, () -> packet.menuSlots().put(41, 7));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.menuSlots().clear());
        assertThrows(UnsupportedOperationException.class, snapshots::clear);
    }

    @Test
    void multipleContainersMapOnlyTheirOwnLogicalCellsAndEmptyDeletesOneContainer() {
        PortalClientCache.update(2, packet(2, "a", Set.of(4), Map.of(40, 4, 41, 5)));
        PortalClientCache.update(2, packet(2, "b", Set.of(8), Map.of(50, 8)));
        assertTrue(PortalClientCache.contains(2, 40));
        assertFalse(PortalClientCache.contains(2, 41));
        assertTrue(PortalClientCache.contains(2, 50));
        assertEquals(2, PortalClientCache.snapshots(2).size());
        PortalClientCache.update(2, packet(2, "a", Set.of(), Map.of()));
        assertFalse(PortalClientCache.contains(2, 40));
        assertTrue(PortalClientCache.contains(2, 50));
        assertEquals(1, PortalClientCache.snapshots(2).size());
        PortalClientCache.update(2, packet(2, "b", Set.of(), Map.of()));
        assertTrue(PortalClientCache.snapshots(2).isEmpty());
    }

    @Test
    void delayedDifferentMenuPacketCannotOverwriteOrDeleteCurrentMenu() {
        PortalClientCache.update(3, packet(3, "a", Set.of(4), Map.of(40, 4)));
        PortalClientCache.update(3, packet(2, "a", Set.of(), Map.of()));
        assertTrue(PortalClientCache.contains(3, 40));
        PortalClientCache.update(3, packet(2, "other", Set.of(8), Map.of(50, 8)));
        assertFalse(PortalClientCache.contains(3, 50));
        assertEquals(1, PortalClientCache.snapshots(3).size());
        assertFalse(PortalClientCache.contains(2, 40));
        assertTrue(PortalClientCache.snapshots(2).isEmpty());
    }

    @Test
    void actualMenuChangeClearsPreviousContainerSnapshotsAndClearResetsAll() {
        PortalClientCache.update(3, packet(3, "a", Set.of(4), Map.of(40, 4)));
        PortalClientCache.update(4, packet(4, "b", Set.of(8), Map.of(50, 8)));
        assertFalse(PortalClientCache.contains(4, 40));
        assertTrue(PortalClientCache.contains(4, 50));
        assertTrue(PortalClientCache.snapshots(3).isEmpty());
        assertEquals(1, PortalClientCache.snapshots(4).size());
        PortalClientCache.clear();
        assertFalse(PortalClientCache.contains(4, 50));
        assertTrue(PortalClientCache.snapshots(4).isEmpty());
    }

    @Test
    void packetCodecRoundTripPreservesMenuContainerAndMapping() {
        PortalSnapshotPacket original = packet(7, "minecraft:overworld|chest_4_64_8", Set.of(4, 13), Map.of(40, 4, 49, 13));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PortalSnapshotPacket.STREAM_CODEC.encode(buffer, original);
            PortalSnapshotPacket decoded = PortalSnapshotPacket.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
            PortalClientCache.update(7, decoded);
            assertTrue(PortalClientCache.contains(7, 40));
            assertTrue(PortalClientCache.contains(7, 49));
        } finally {
            buffer.release();
        }
    }

    @Test
    void decoderRejectsOutOfBoundsCellAndLogicalMapping() {
        for (boolean badCell : new boolean[]{true, false}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(1).writeUtf("one").writeVarInt(9).writeVarInt(27);
                buffer.writeVarInt(badCell ? 1 : 0);
                if (badCell) buffer.writeVarInt(27);
                else buffer.writeVarInt(1).writeVarInt(40).writeVarInt(27);
                assertThrows(IllegalArgumentException.class, () -> PortalSnapshotPacket.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }
}
