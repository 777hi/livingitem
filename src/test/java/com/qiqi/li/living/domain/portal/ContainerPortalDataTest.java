package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NbtOps;

import org.junit.jupiter.api.Test;

/** 不可变区域、快照和门域持久编码守卫。 */
class ContainerPortalDataTest {
    @Test
    void constructorsCopyBothCollectionLevels() {
        Set<Integer> slots = new HashSet<>(Set.of(4));
        List<ContainerPortalData.PortalRegion> regions = new ArrayList<>();
        regions.add(new ContainerPortalData.PortalRegion(3, slots));
        ContainerPortalData data = new ContainerPortalData(regions);
        slots.clear();
        regions.clear();
        assertEquals(Set.of(4), data.portalSlots());
        assertThrows(UnsupportedOperationException.class, () -> data.regions().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.regions().getFirst().slots().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.portalSlots().clear());
    }

    @Test
    void emptyNeverSharesMutableStateWithUpdatedData() {
        ContainerPortalData data = ContainerPortalData.EMPTY.withRegion(3, Set.of(4));
        assertTrue(ContainerPortalData.EMPTY.isEmpty());
        assertFalse(data.isEmpty());
        assertTrue(data.contains(4));
        assertFalse(data.contains(5));
        assertTrue(data.withoutRegion(data.regions().getFirst()).isEmpty());
        assertFalse(data.isEmpty());
        assertTrue(ContainerPortalData.EMPTY.snapshot().isEmpty());
    }

    @Test
    void snapshotHasIndependentRegionCollections() {
        ContainerPortalData original = ContainerPortalData.EMPTY.withRegion(5, Set.of(6, 7, 8));
        ContainerPortalData snapshot = original.snapshot();
        assertEquals(original, snapshot);
        assertNotSame(original, snapshot);
        assertNotSame(original.regions(), snapshot.regions());
        assertNotSame(original.regions().getFirst(), snapshot.regions().getFirst());
        assertNotSame(original.regions().getFirst().slots(), snapshot.regions().getFirst().slots());
    }

    @Test
    void multipleRegionsRemainIndependentAndNeverMergeOnOverlap() {
        ContainerPortalData data = ContainerPortalData.EMPTY.withRegion(5, Set.of(6)).withRegion(5, Set.of(8));
        assertEquals(2, data.regions().size());
        assertSame(data, data.withRegion(5, Set.of(6)));
        assertSame(data, data.withRegion(5, Set.of(6, 7, 8)));
        ContainerPortalData removed = data.withoutRegion(data.regions().getFirst());
        assertEquals(Set.of(8), removed.portalSlots());
        assertSame(removed, removed.withoutRegion(new ContainerPortalData.PortalRegion(5, Set.of(6))));
    }

    @Test
    void codecPreservesLogicalSlotSetsAndWidths() {
        ContainerPortalData data = ContainerPortalData.EMPTY.withRegion(5, Set.of(6, 7)).withRegion(5, Set.of(8));
        var encoded = ContainerPortalData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        ContainerPortalData decoded = ContainerPortalData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(data, decoded);
        assertEquals(5, decoded.regions().getFirst().width());
        assertNotSame(data, decoded);
        assertTrue(ContainerPortalData.CODEC.parse(NbtOps.INSTANCE,
            ContainerPortalData.CODEC.encodeStart(NbtOps.INSTANCE, ContainerPortalData.EMPTY).getOrThrow())
            .getOrThrow().isEmpty());
    }

    @Test
    void playerKeyedCodecKeepsSeparateContainers() {
        ContainerPortalData first = ContainerPortalData.EMPTY.withRegion(3, Set.of(4));
        ContainerPortalData second = ContainerPortalData.EMPTY.withRegion(5, Set.of(6));
        Map<String, ContainerPortalData> map = Map.of("first", first, "second", second);
        var encoded = ContainerPortalData.KEYED_CODEC.encodeStart(NbtOps.INSTANCE, map).getOrThrow();
        assertEquals(map, ContainerPortalData.KEYED_CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void invalidRegionValuesAndOverlapsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerPortalData.PortalRegion(0, Set.of(4)));
        assertThrows(IllegalArgumentException.class, () -> new ContainerPortalData.PortalRegion(3, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContainerPortalData.PortalRegion(3, Set.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ContainerPortalData(List.of(
            new ContainerPortalData.PortalRegion(3, Set.of(4)),
            new ContainerPortalData.PortalRegion(3, Set.of(4)))));
    }

    @Test
    void codecRejectsMalformedRegionsWithoutThrowingConstructorErrors() throws Exception {
        var invalidWidth = net.minecraft.nbt.TagParser.parseTag("{width:0,slots:[4]}");
        var emptySlots = net.minecraft.nbt.TagParser.parseTag("{width:3,slots:[]}");
        var negativeSlot = net.minecraft.nbt.TagParser.parseTag("{width:3,slots:[-1]}");
        assertTrue(ContainerPortalData.PortalRegion.CODEC.parse(NbtOps.INSTANCE, invalidWidth).error().isPresent());
        assertTrue(ContainerPortalData.PortalRegion.CODEC.parse(NbtOps.INSTANCE, emptySlots).error().isPresent());
        assertTrue(ContainerPortalData.PortalRegion.CODEC.parse(NbtOps.INSTANCE, negativeSlot).error().isPresent());
        net.minecraft.nbt.ListTag overlapping = new net.minecraft.nbt.ListTag();
        overlapping.add(net.minecraft.nbt.TagParser.parseTag("{width:3,slots:[4]}"));
        overlapping.add(net.minecraft.nbt.TagParser.parseTag("{width:3,slots:[4]}"));
        assertTrue(ContainerPortalData.CODEC.parse(NbtOps.INSTANCE, overlapping).error().isPresent());
    }

    @Test
    void keyDeclaresSavedCodecAndEmptyTest() {
        assertTrue(ContainerPortalData.KEY.isSaved());
        assertSame(ContainerPortalData.CODEC, ContainerPortalData.KEY.codec());
        assertTrue(ContainerPortalData.KEY.isEmpty(ContainerPortalData.EMPTY));
        assertFalse(ContainerPortalData.KEY.isEmpty(ContainerPortalData.EMPTY.withRegion(3, Set.of(4))));
        assertNotNull(PortalComponents.CONTAINER_PORTAL_DATA.value());
        assertNotNull(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER.value());
        assertNotNull(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.value());
    }
}
