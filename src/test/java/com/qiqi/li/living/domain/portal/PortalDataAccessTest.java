package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 门域热表、Player/UUID/附件回填与仅清自身 key 守卫。 */
class PortalDataAccessTest {
    private UUID usedUuid;

    @AfterEach
    void clean() {
        ContainerLivingItemHandler.clearAllCaches();
        if (usedUuid != null) ContainerDataSavedData.get().remove(usedUuid, ContainerPortalData.KEY);
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
        when(host.getData(com.qiqi.li.living.components.LivingComponents.CONTAINER_UUID_ATTACHMENT.value()))
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
