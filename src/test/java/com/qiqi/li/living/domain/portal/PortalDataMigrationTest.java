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

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerHandoff;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 保留内容的门域交接、销毁与旧热数据替换守卫。 */
class PortalDataMigrationTest {
    private UUID usedUuid;
    private ContainerDataKey<ContainerPortalData> otherSavedKey;

    @AfterEach
    void clean() {
        ContainerHandoff.discard();
        ContainerLivingItemHandler.clearAllCaches();
        if (usedUuid != null) {
            ContainerDataSavedData.get().remove(usedUuid, ContainerPortalData.KEY);
            if (otherSavedKey != null) ContainerDataSavedData.get().remove(usedUuid, otherSavedKey);
        }
    }

    private static ContainerPortalData door() {
        return ContainerPortalData.EMPTY.withRegion(3, Set.of(4));
    }

    private static BlockEntity retainingHost(ContainerPortalData data) {
        BlockEntity host = mock(BlockEntity.class);
        doReturn(BlockEntityType.SHULKER_BOX).when(host).getType();
        Map<AttachmentType<?>, Object> map = new HashMap<>();
        map.put(PortalComponents.CONTAINER_PORTAL_DATA.value(), data);
        doAnswer(inv -> map.get(inv.getArgument(0))).when(host).getData(any(AttachmentType.class));
        doAnswer(inv -> map.get(inv.getArgument(0))).when(host).getExistingDataOrNull(any(AttachmentType.class));
        when(host.getData(PortalComponents.CONTAINER_PORTAL_DATA)).thenAnswer(inv ->
            map.get(PortalComponents.CONTAINER_PORTAL_DATA.value()));
        doAnswer(inv -> {
            map.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(host).setData(any(AttachmentType.class), any());
        return host;
    }

    private static ServerLevel levelWithoutCapability() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        return level;
    }

    @Test
    void noUuidMoveOutAndInCarryIndependentSnapshots() {
        ContainerPortalData data = door();
        BlockEntity oldHost = retainingHost(data);
        ItemStack carrier = new ItemStack(Items.SHULKER_BOX);
        assertTrue(PortalDataMigration.moveOut(oldHost, carrier));
        ContainerPortalData carried = carrier.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get());
        assertEquals(data, carried);
        assertNotSame(data, carried);
        assertTrue(oldHost.getData(PortalComponents.CONTAINER_PORTAL_DATA).isEmpty());
        BlockEntity newHost = retainingHost(ContainerPortalData.EMPTY);
        PortalDataMigration.moveIn(newHost, carrier);
        ContainerPortalData restored = newHost.getData(PortalComponents.CONTAINER_PORTAL_DATA);
        assertEquals(data, restored);
        assertNotSame(carried, restored);
        assertEquals(data, carrier.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get()));
    }

    @Test
    void noUuidSnapshotDoesNotKeepTargetsUnrelatedOldIdentity() {
        BlockEntity host = retainingHost(door());
        ItemStack target = new ItemStack(Items.SHULKER_BOX);
        target.set(LivingComponents.CONTAINER_UUID.get(), UUID.randomUUID());
        assertTrue(PortalDataMigration.moveOut(host, target));
        assertNull(target.get(LivingComponents.CONTAINER_UUID.get()));
        assertEquals(door(), target.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get()));
    }

    @Test
    void creativeRepeatedPlacementDoesNotShareRegionCollections() {
        ItemStack source = new ItemStack(Items.SHULKER_BOX);
        source.set(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get(), door());
        BlockEntity first = retainingHost(ContainerPortalData.EMPTY);
        BlockEntity second = retainingHost(ContainerPortalData.EMPTY);
        PortalDataMigration.moveIn(first, source);
        PortalDataMigration.moveIn(second, source);
        ContainerPortalData one = first.getData(PortalComponents.CONTAINER_PORTAL_DATA);
        ContainerPortalData two = second.getData(PortalComponents.CONTAINER_PORTAL_DATA);
        assertEquals(one, two);
        assertNotSame(one, two);
        assertNotSame(one.regions().getFirst().slots(), two.regions().getFirst().slots());
    }

    @Test
    void emptyTargetMissingDataAndNonRetainingHostDoNotMove() {
        assertFalse(PortalDataMigration.moveOut(null, ItemStack.EMPTY));
        BlockEntity empty = retainingHost(ContainerPortalData.EMPTY);
        assertFalse(PortalDataMigration.moveOut(empty, new ItemStack(Items.SHULKER_BOX)));
        BlockEntity chest = retainingHost(door());
        doReturn(BlockEntityType.CHEST).when(chest).getType();
        ItemStack target = new ItemStack(Items.CHEST);
        assertFalse(PortalDataMigration.moveOut(chest, target));
        assertNull(target.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get()));
        assertEquals(door(), chest.getData(PortalComponents.CONTAINER_PORTAL_DATA));
    }

    @Test
    void uuidIsPreservedAndOnlySavedDataCarriesDoor() {
        usedUuid = UUID.randomUUID();
        ContainerDataSavedData.get().put(usedUuid, ContainerPortalData.KEY, door());
        BlockEntity oldHost = retainingHost(ContainerPortalData.EMPTY);
        oldHost.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), usedUuid);
        ItemStack target = new ItemStack(Items.SHULKER_BOX);
        target.set(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get(), door());
        assertTrue(PortalDataMigration.moveOut(oldHost, target));
        assertEquals(usedUuid, target.get(LivingComponents.CONTAINER_UUID.get()));
        assertNull(target.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get()));
        assertEquals(door(), ContainerDataSavedData.get().get(usedUuid, ContainerPortalData.KEY));
        BlockEntity newHost = retainingHost(ContainerPortalData.EMPTY);
        PortalDataMigration.moveIn(newHost, target);
        assertEquals(usedUuid, newHost.getData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get()));
        assertTrue(newHost.getData(PortalComponents.CONTAINER_PORTAL_DATA).isEmpty());
    }

    @Test
    void removedHostStillClearsOldHotKeyUsingExistingContextIdentity() {
        ServerLevel level = levelWithoutCapability();
        BlockEntity oldHost = retainingHost(door());
        when(oldHost.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(oldHost.getLevel()).thenReturn(level);
        SimpleContainerContext oldCtx = new SimpleContainerContext(new ItemStackHandler(9), null,
            List.of(BlockPos.ZERO), List.of(oldHost), level);
        PortalDataAccess.persist(oldCtx, door());
        ItemStack target = new ItemStack(Items.SHULKER_BOX);
        assertTrue(PortalDataMigration.moveOut(oldHost, target));
        assertNull(ContainerLivingItemHandler.peekContainerData(oldCtx, ContainerPortalData.KEY));
        assertEquals(door(), target.get(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get()));
    }

    @Test
    void restoreReplacesPreexistingEmptyHotDataNotOnlyAttachment() {
        ServerLevel level = levelWithoutCapability();
        BlockEntity newHost = retainingHost(ContainerPortalData.EMPTY);
        when(newHost.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(newHost.getLevel()).thenReturn(level);
        SimpleContainerContext ctx = new SimpleContainerContext(new ItemStackHandler(9), null,
            List.of(BlockPos.ZERO), List.of(newHost), level);
        ContainerLivingItemHandler.putContainerData(ctx, ContainerPortalData.KEY, ContainerPortalData.EMPTY);
        ItemStack source = new ItemStack(Items.SHULKER_BOX);
        source.set(PortalComponents.CONTAINER_PORTAL_DATA_ITEM.get(), door());
        PortalDataMigration.moveIn(newHost, source);
        assertEquals(door(), ContainerLivingItemHandler.peekContainerData(ctx, ContainerPortalData.KEY));
        assertEquals(door(), newHost.getData(PortalComponents.CONTAINER_PORTAL_DATA));
    }

    @Test
    void destroyingUuidContainerRemovesOnlyPortalSavedKey() {
        usedUuid = UUID.randomUUID();
        otherSavedKey = ContainerDataKey.savedWith("portal_test_other_saved", () -> ContainerPortalData.EMPTY,
            () -> PortalComponents.CONTAINER_PORTAL_DATA.value(), () -> ContainerPortalData.CODEC);
        ContainerDataSavedData.get().put(usedUuid, ContainerPortalData.KEY, door());
        ContainerDataSavedData.get().put(usedUuid, otherSavedKey, door());
        BlockEntity host = retainingHost(door());
        doReturn(BlockEntityType.CHEST).when(host).getType();
        when(host.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState());
        host.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), usedUuid);
        BlockDropsEvent event = mock(BlockDropsEvent.class);
        ServerLevel level = levelWithoutCapability();
        when(event.getBlockEntity()).thenReturn(host);
        when(event.getLevel()).thenReturn(level);
        PortalDataMigration.onBlockDrops(event);
        assertNull(ContainerDataSavedData.get().get(usedUuid, ContainerPortalData.KEY));
        assertEquals(door(), ContainerDataSavedData.get().get(usedUuid, otherSavedKey));
    }

    @Test
    void doubleChestSplitAfterWorldTurnsSingleClearsOldPortalKeyOnly() {
        ServerLevel level = levelWithoutCapability();
        BlockEntity broken = retainingHost(door());
        BlockEntity surviving = retainingHost(door());
        BlockPos leftPos = BlockPos.ZERO;
        BlockPos rightPos = leftPos.east();
        when(broken.getBlockPos()).thenReturn(leftPos);
        when(broken.getLevel()).thenReturn(level);
        when(broken.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        when(surviving.getBlockPos()).thenReturn(rightPos);
        when(surviving.getLevel()).thenReturn(level);
        when(surviving.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState());
        when(level.getBlockEntity(rightPos)).thenReturn(surviving);
        when(level.getBlockState(rightPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        SimpleContainerContext oldContext = new SimpleContainerContext(new ItemStackHandler(54), null,
            List.of(leftPos, rightPos), List.of(broken, surviving), level);
        ContainerDataKey<Integer> unrelated = ContainerDataKey.persistent("portal_test_split_other", () -> 0);
        ContainerLivingItemHandler.putContainerData(oldContext, unrelated, 42);
        PortalDataAccess.persist(oldContext, door());

        PortalDataMigration.clearPortalOnChestSplit(level, broken);

        assertNull(ContainerLivingItemHandler.peekContainerData(oldContext, ContainerPortalData.KEY));
        assertEquals(42, ContainerLivingItemHandler.peekContainerData(oldContext, unrelated));
        assertTrue(broken.getData(PortalComponents.CONTAINER_PORTAL_DATA).isEmpty());
        assertTrue(surviving.getData(PortalComponents.CONTAINER_PORTAL_DATA).isEmpty());
        assertTrue(PortalDataAccess.getOrLoad(oldContext).isEmpty());
    }

    @Test
    void doubleChestRightHalfSplitClearsOldLeftThenRightKey() {
        ServerLevel level = levelWithoutCapability();
        BlockEntity surviving = retainingHost(door());
        BlockEntity broken = retainingHost(door());
        BlockPos leftPos = BlockPos.ZERO;
        BlockPos rightPos = leftPos.east();
        when(broken.getBlockPos()).thenReturn(rightPos);
        when(broken.getLevel()).thenReturn(level);
        when(broken.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        when(surviving.getBlockPos()).thenReturn(leftPos);
        when(surviving.getLevel()).thenReturn(level);
        when(surviving.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState());
        when(level.getBlockEntity(leftPos)).thenReturn(surviving);
        SimpleContainerContext oldContext = new SimpleContainerContext(new ItemStackHandler(54), null,
            List.of(leftPos, rightPos), List.of(surviving, broken), level);
        PortalDataAccess.persist(oldContext, door());

        PortalDataMigration.clearPortalOnChestSplit(level, broken);

        assertNull(ContainerLivingItemHandler.peekContainerData(oldContext, ContainerPortalData.KEY));
        assertTrue(PortalDataAccess.getOrLoad(oldContext).isEmpty());
    }

    @Test
    void doubleChestSplitClearsBothAttachments() {
        ServerLevel level = levelWithoutCapability();
        BlockEntity broken = retainingHost(door());
        when(broken.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(broken.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        BlockEntity other = retainingHost(door());
        when(level.getBlockEntity(BlockPos.ZERO.east())).thenReturn(other);
        PortalDataMigration.clearPortalOnChestSplit(level, broken);
        assertTrue(broken.getData(PortalComponents.CONTAINER_PORTAL_DATA).isEmpty());
        assertTrue(other.getData(PortalComponents.CONTAINER_PORTAL_DATA).isEmpty());
    }

}
