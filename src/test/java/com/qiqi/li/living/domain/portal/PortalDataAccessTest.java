package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 门域热表、Player/UUID/附件回填与仅清自身 key 守卫。 */
class PortalDataAccessTest {
    private UUID usedUuid;
    private final Map<String, Map<Object, Object>> cacheMaps = new HashMap<>();
    private final Map<String, Map<Object, Object>> previousCacheMaps = new HashMap<>();
    private List<Object> pendingInvalidations;
    private List<Object> previousInvalidations;
    private long previousSweep;
    private ContainerDataSavedData saved;
    private MockedStatic<ContainerDataSavedData> savedAccess;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void isolateCachesAndSavedData() throws ReflectiveOperationException {
        for (String name : List.of("CONTAINER_DATA", "POS_TO_CACHE_KEY", "LAST_SEEN", "LIVING_SLOT_ITEMS")) {
            Map<Object, Object> cache = (Map<Object, Object>) field(name).get(null);
            cacheMaps.put(name, cache);
            previousCacheMaps.put(name, new HashMap<>(cache));
        }
        pendingInvalidations = (List<Object>) field("PENDING_RUNTIME_INVALIDATIONS").get(null);
        previousInvalidations = new ArrayList<>(pendingInvalidations);
        previousSweep = field("lastSweepMs").getLong(null);
        ContainerLivingItemHandler.clearAllCaches();
        saved = ContainerDataSavedData.load(new CompoundTag(), null);
        savedAccess = mockStatic(ContainerDataSavedData.class, CALLS_REAL_METHODS);
        savedAccess.when(ContainerDataSavedData::get).thenReturn(saved);
    }

    @AfterEach
    void clean() throws ReflectiveOperationException {
        try {
            if (usedUuid != null && saved != null) saved.remove(usedUuid, ContainerPortalData.KEY);
        } finally {
            if (savedAccess != null) savedAccess.close();
            for (String name : previousCacheMaps.keySet()) {
                Map<Object, Object> cache = cacheMaps.get(name);
                cache.clear();
                cache.putAll(previousCacheMaps.get(name));
            }
            if (previousInvalidations != null) {
                pendingInvalidations.clear();
                pendingInvalidations.addAll(previousInvalidations);
                field("lastSweepMs").setLong(null, previousSweep);
            }
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = ContainerLivingItemHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Map<String, Map<Object, Object>> coldCacheSnapshot() {
        Map<String, Map<Object, Object>> snapshot = new HashMap<>();
        for (String name : List.of("CONTAINER_DATA", "POS_TO_CACHE_KEY", "LAST_SEEN")) {
            snapshot.put(name, new HashMap<>(cacheMaps.get(name)));
        }
        return snapshot;
    }

    private void assertColdCachesUnchanged(Map<String, Map<Object, Object>> before) {
        for (String name : before.keySet()) assertEquals(before.get(name), cacheMaps.get(name), name);
    }

    private static SimpleContainerContext blockContext(int size, ChestBlockEntity... hosts) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        List<BlockPos> positions = new ArrayList<>();
        List<BlockEntity> entities = new ArrayList<>();
        for (ChestBlockEntity host : hosts) {
            positions.add(host.getBlockPos());
            entities.add(host);
            host.setLevel(level);
        }
        return new SimpleContainerContext(new ItemStackHandler(size), null, positions, entities, level);
    }

    private static ChestBlockEntity chest(BlockPos pos) {
        return new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
    }

    @Test
    void realSimpleContextEmptyColdPeekNeverCreatesCacheIndicesOrUuidAttachment() {
        ChestBlockEntity host = chest(new BlockPos(114, 64, 118));
        SimpleContainerContext ctx = blockContext(9, host);
        Map<String, Map<Object, Object>> before = coldCacheSnapshot();
        CompoundTag savedBefore = saved.save(new CompoundTag(), null).copy();
        assertFalse(host.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(host.hasData(PortalComponents.CONTAINER_PORTAL_DATA));

        assertSame(ContainerPortalData.EMPTY, PortalDataAccess.peekSaved(ctx));
        assertSame(ContainerPortalData.EMPTY, PortalDataAccess.peekSaved(ctx));

        assertColdCachesUnchanged(before);
        assertFalse(host.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(host.hasData(PortalComponents.CONTAINER_PORTAL_DATA));
        assertNull(ctx.getContainerUuid());
        assertEquals(savedBefore, saved.save(new CompoundTag(), null));
        assertFalse(saved.isDirty());
    }

    @Test
    void realSimpleContextExistingUuidReadsSavedDoorWithoutCreatingHotState() {
        usedUuid = UUID.randomUUID();
        ChestBlockEntity host = chest(new BlockPos(124, 64, 128));
        host.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), usedUuid);
        ContainerPortalData data = door(3, 4);
        saved.put(usedUuid, ContainerPortalData.KEY, data);
        saved.setDirty(false);
        SimpleContainerContext ctx = blockContext(9, host);
        Map<String, Map<Object, Object>> before = coldCacheSnapshot();
        CompoundTag savedBefore = saved.save(new CompoundTag(), null).copy();

        assertEquals(data, PortalDataAccess.peekSaved(ctx));
        assertEquals(data, PortalDataAccess.peekSaved(ctx));

        assertColdCachesUnchanged(before);
        assertEquals(usedUuid, host.getExistingDataOrNull(LivingComponents.CONTAINER_UUID_ATTACHMENT.get()));
        assertFalse(host.hasData(PortalComponents.CONTAINER_PORTAL_DATA));
        assertEquals(savedBefore, saved.save(new CompoundTag(), null));
        assertFalse(saved.isDirty());
    }

    @Test
    void realDoubleHostColdPeekSkipsFirstEmptyAttachmentAndMatchesSubsequentLoad() {
        ChestBlockEntity first = chest(new BlockPos(134, 64, 138));
        ChestBlockEntity second = chest(new BlockPos(135, 64, 138));
        ContainerPortalData data = door(9, 10);
        first.setData(PortalComponents.CONTAINER_PORTAL_DATA.get(), ContainerPortalData.EMPTY);
        second.setData(PortalComponents.CONTAINER_PORTAL_DATA.get(), data);
        SimpleContainerContext ctx = blockContext(54, first, second);
        Map<String, Map<Object, Object>> before = coldCacheSnapshot();

        ContainerPortalData candidate = PortalDataAccess.peekSaved(ctx);
        assertEquals(data, candidate);
        assertEquals(candidate, PortalDataAccess.peekSaved(ctx));
        assertColdCachesUnchanged(before);
        assertFalse(first.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(second.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertEquals(ContainerPortalData.EMPTY, first.getExistingDataOrNull(PortalComponents.CONTAINER_PORTAL_DATA.get()));
        assertEquals(data, second.getExistingDataOrNull(PortalComponents.CONTAINER_PORTAL_DATA.get()));
        assertEquals(0, saved.size());
        assertFalse(saved.isDirty());

        assertEquals(candidate, PortalDataAccess.getOrLoad(ctx));
        assertFalse(first.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
        assertFalse(second.hasData(LivingComponents.CONTAINER_UUID_ATTACHMENT));
    }

    private static ContainerPortalData door(int width, int slot) {
        return ContainerPortalData.EMPTY.withRegion(width, Set.of(slot));
    }

    private static FakeContainerContext playerContext(Player player, String key) {
        return new FakeContainerContext(9, 3, key) {
            @Override public Player getOwnerPlayer() { return player; }
        };
    }

    @Test
    void persistReplacesHotDataWithIndependentSnapshot() {
        FakeContainerContext ctx = new FakeContainerContext(9, 3);
        ContainerPortalData data = door(3, 4);
        PortalDataAccess.persist(ctx, data);
        ContainerPortalData hot = PortalDataAccess.getOrLoad(ctx);
        assertEquals(data, hot);
        assertNotSame(data, hot);
        assertSame(hot, PortalDataAccess.getOrLoad(ctx));
    }

    @Test
    void playerMapBackfillsAndClearDoesNotTouchOtherContainer() {
        Player player = mock(Player.class);
        String key = "player_" + UUID.randomUUID();
        String otherKey = key + "_ender_chest";
        Map<String, ContainerPortalData> map = new HashMap<>(Map.of(key, door(3, 4), otherKey, door(5, 6)));
        when(player.getData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER)).thenAnswer(inv -> map);
        doAnswer(inv -> {
            Map<String, ContainerPortalData> next = inv.getArgument(1);
            map.clear();
            map.putAll(next);
            return null;
        }).when(player).setData(eq(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER.value()), any());
        FakeContainerContext ctx = playerContext(player, key);
        assertEquals(Set.of(4), PortalDataAccess.getOrLoad(ctx).portalSlots());
        PortalDataAccess.clear(ctx);
        assertFalse(map.containsKey(key));
        assertEquals(Set.of(6), map.get(otherKey).portalSlots());
        ContainerLivingItemHandler.clearAllCaches();
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
    }

    @Test
    void playerPersistCopiesDecodedMapAndPayload() {
        Player player = mock(Player.class);
        String key = "player_" + UUID.randomUUID();
        Map<String, ContainerPortalData> initial = Map.of(key + "_ender_chest", door(5, 6));
        when(player.getData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER)).thenReturn(initial);
        FakeContainerContext ctx = playerContext(player, key);
        ContainerPortalData data = door(3, 4);
        PortalDataAccess.persist(ctx, data);
        @SuppressWarnings("unchecked")
        var captor = (org.mockito.ArgumentCaptor<Map<String, ContainerPortalData>>) (org.mockito.ArgumentCaptor<?>)
            org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(player).setData(eq(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER.value()), captor.capture());
        assertEquals(2, captor.getValue().size());
        assertNotSame(initial, captor.getValue());
        assertNotSame(data, captor.getValue().get(key));
        assertEquals(1, initial.size());
    }

    @Test
    void worldContainerWithOwnerNeverLoadsOrWritesPlayersDoor() {
        Player player = mock(Player.class);
        FakeContainerContext ctx = playerContext(player, "chest_0_0_0");
        PortalDataAccess.persist(ctx, door(3, 4));
        PortalDataAccess.getOrLoad(ctx);
        verify(player, never()).getData(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER);
        verify(player, never()).setData(eq(PortalComponents.CONTAINER_PORTAL_DATA_PLAYER.value()), any());
    }

    @Test
    void clearRemovesOnlyPortalHotKey() {
        FakeContainerContext ctx = new FakeContainerContext(9, 3);
        ContainerDataKey<Integer> unrelated = ContainerDataKey.persistent("portal_test_unrelated", () -> 0);
        ContainerLivingItemHandler.putContainerData(ctx, unrelated, 42);
        PortalDataAccess.persist(ctx, door(3, 4));
        PortalDataAccess.clear(ctx);
        assertNull(ContainerLivingItemHandler.peekContainerData(ctx, ContainerPortalData.KEY));
        assertEquals(42, ContainerLivingItemHandler.peekContainerData(ctx, unrelated));
    }

    @Test
    void uuidPersistReloadAndClearUseSavedDataNotPlayerMap() {
        usedUuid = UUID.randomUUID();
        UUID uuid = usedUuid;
        FakeContainerContext ctx = new FakeContainerContext(9, 3, ContainerUuids.keyOf(uuid)) {
            @Override public UUID getContainerUuid() { return uuid; }
        };
        ContainerPortalData data = door(3, 4);
        PortalDataAccess.persist(ctx, data);
        ContainerLivingItemHandler.clearAllCaches();
        assertEquals(data, PortalDataAccess.getOrLoad(ctx));
        CompoundTag tag = ContainerDataSavedData.get().save(new CompoundTag(), null);
        assertEquals(data, ContainerDataSavedData.load(tag, null).get(uuid, ContainerPortalData.KEY));
        PortalDataAccess.clear(ctx);
        assertNull(ContainerDataSavedData.get().get(uuid, ContainerPortalData.KEY));
        ContainerLivingItemHandler.clearAllCaches();
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
    }

    @Test
    void explicitUuidBackfillWorksWithoutUuidGetter() {
        usedUuid = UUID.randomUUID();
        ContainerPortalData data = door(3, 4);
        ContainerDataSavedData.get().put(usedUuid, ContainerPortalData.KEY, data);
        FakeContainerContext ctx = new FakeContainerContext(9, 3, ContainerUuids.keyOf(usedUuid));
        assertEquals(data, PortalDataAccess.getOrLoad(ctx, usedUuid));
    }

    @Test
    void missingUuidSavedDataNeverResurrectsStaleAttachment() {
        usedUuid = UUID.randomUUID();
        BlockEntity host = mock(BlockEntity.class);
        when(host.getData(PortalComponents.CONTAINER_PORTAL_DATA.value())).thenReturn(door(3, 4));
        when(host.getExistingDataOrNull(LivingComponents.CONTAINER_UUID_ATTACHMENT.get()))
            .thenReturn(usedUuid);
        SimpleContainerContext ctx = new SimpleContainerContext(new ItemStackHandler(9),
            List.of(BlockPos.ZERO), List.of(host));
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
    }

    @Test
    void emptyPersistUsesClearAndLeavesNoStaleAttachment() {
        BlockEntity host = mock(BlockEntity.class);
        Map<net.neoforged.neoforge.attachment.AttachmentType<?>, Object> attachments = new HashMap<>();
        doAnswer(inv -> attachments.get(inv.getArgument(0))).when(host).getData(any(net.neoforged.neoforge.attachment.AttachmentType.class));
        doAnswer(inv -> {
            attachments.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(host).setData(any(net.neoforged.neoforge.attachment.AttachmentType.class), any());
        ContainerContext ctx = new SimpleContainerContext(new ItemStackHandler(9), List.of(BlockPos.ZERO), List.of(host));
        PortalDataAccess.persist(ctx, door(3, 4));
        ContainerLivingItemHandler.clearAllCaches();
        assertEquals(Set.of(4), PortalDataAccess.getOrLoad(ctx).portalSlots());
        PortalDataAccess.persist(ctx, ContainerPortalData.EMPTY);
        ContainerLivingItemHandler.clearAllCaches();
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
        assertTrue(((ContainerPortalData) attachments.get(PortalComponents.CONTAINER_PORTAL_DATA.value())).isEmpty());
    }
}
