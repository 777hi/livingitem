package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.util.StaticCacheRegistry;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/** 门快照持续发送、真实菜单映射与空快照清理守卫。 */
class PortalServerSyncTest {

    @AfterEach
    void cleanCaches() {
        StaticCacheRegistry.runServer(mock(MinecraftServer.class));
        ContainerLivingItemHandler.clearAllCaches();
        PortalClientCache.clear();
    }

    private static final class Context extends FakeContainerContext {
        final ServerLevel level;
        Context(ServerLevel level) {
            super(9, 3, "portal_sync_test_" + UUID.randomUUID());
            this.level = level;
            withBlockPos(BlockPos.ZERO);
        }
        @Override public Level getLevel() { return level; }
    }

    private static ContainerPortalData door() {
        return ContainerPortalData.EMPTY.withRegion(9, java.util.Set.of(4));
    }

    @Test
    void noDataDoesNotNeedServerOrPlayerListAndMissingPlayerListIsNoOp() {
        ServerLevel level = mock(ServerLevel.class);
        Context ctx = new Context(level);
        PortalServerSync.flush(ctx, new TickContext(ctx));
        verify(level, never()).getServer();
        PortalDataAccess.persist(ctx, ContainerPortalData.EMPTY.withRegion(3, java.util.Set.of(4)));
        assertDoesNotThrow(() -> PortalServerSync.flush(ctx, new TickContext(ctx)), "没有服务器时是边界空操作");
        MinecraftServer server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        assertDoesNotThrow(() -> PortalServerSync.flush(ctx, new TickContext(ctx)), "没有 playerList 时是边界空操作");
        verify(server, times(1)).getPlayerList();
    }

    private static final class Fixture {
        final BlockPos pos = new BlockPos(4, 64, 8);
        final ServerLevel level = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerList players = mock(PlayerList.class);
        final ServerPlayer viewer;
        final ServerPlayer foreignViewer;
        final ChestBlockEntity chest;
        final ContainerContext ctx;

        Fixture() {
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getServer()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(players);
            viewer = player();
            foreignViewer = player();
            foreignViewer.containerMenu = ChestMenu.threeRows(1, foreignViewer.getInventory(), new SimpleContainer(27));
            when(players.getPlayers()).thenReturn(List.of(viewer, foreignViewer));
            chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
            chest.setLevel(level);
            when(level.getBlockEntity(pos)).thenReturn(chest);
            when(level.getBlockState(pos)).thenReturn(chest.getBlockState());
            doReturn(new InvWrapper(chest)).when(level).getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            viewer.containerMenu = ChestMenu.threeRows(1, viewer.getInventory(), chest);
            ctx = ContainerContexts.resolve(viewer, viewer.containerMenu.slots.getFirst());
            assertNotNull(ctx);
        }

        private ServerPlayer player() {
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(level);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.isSpectator()).thenReturn(true);
            Inventory inventory = new Inventory(player);
            when(player.getInventory()).thenReturn(inventory);
            player.containerMenu = new InventoryMenu(inventory, false, player);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            return player;
        }
    }

    @Test
    void packetMapsOnlyRealChestSlotsNotForeignPlayerInventoryOrInactiveSlots() {
        Fixture f = new Fixture();
        PortalSnapshotPacket packet = PortalServerSync.buildPacket(f.ctx, door(), f.viewer);
        assertNotNull(packet);
        assertEquals(1, packet.menuId());
        assertEquals(f.ctx.getCacheKey(), packet.containerKey());
        assertEquals(27, packet.menuSlots().size());
        for (int slot = 0; slot < 27; slot++) assertEquals(slot, packet.menuSlots().get(slot));
        assertFalse(packet.menuSlots().containsKey(27), "玩家物品栏不是箱子自己的存储槽");
        assertEquals(java.util.Set.of(4), packet.cells());
        assertNull(PortalServerSync.buildPacket(f.ctx, door(), f.foreignViewer));
    }

    @Test
    void repeatedSnapshotKeepsSendingAndExtinguishingSendsExactlyOneEmptyPacket() {
        Fixture f = new Fixture();
        PortalDataAccess.persist(f.ctx, door());
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        PortalDataAccess.clear(f.ctx);
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        ArgumentCaptor<PortalSnapshotPacket> sent = ArgumentCaptor.forClass(PortalSnapshotPacket.class);
        verify(f.viewer.connection, times(3)).send(sent.capture());
        assertEquals(java.util.Set.of(4), sent.getAllValues().get(0).cells());
        assertEquals(sent.getAllValues().get(0), sent.getAllValues().get(1));
        assertTrue(sent.getAllValues().get(2).cells().isEmpty());
        assertEquals(f.ctx.getCacheKey(), sent.getAllValues().get(2).containerKey());
        verifyNoInteractions(f.foreignViewer.connection);
    }

    @Test
    void clearingClientCacheWithoutServerOpenStillRestoresDoorOnNextTick() {
        Fixture f = new Fixture();
        PortalDataAccess.persist(f.ctx, door());
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        ArgumentCaptor<PortalSnapshotPacket> sent = ArgumentCaptor.forClass(PortalSnapshotPacket.class);
        verify(f.viewer.connection).send(sent.capture());
        PortalClientCache.update(f.viewer.containerMenu.containerId, sent.getValue());
        assertTrue(PortalClientCache.contains(f.viewer.containerMenu.containerId, 4));

        PortalClientCache.clear();
        assertFalse(PortalClientCache.contains(f.viewer.containerMenu.containerId, 4));
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        verify(f.viewer.connection, times(2)).send(sent.capture());
        PortalClientCache.update(f.viewer.containerMenu.containerId, sent.getValue());
        assertTrue(PortalClientCache.contains(f.viewer.containerMenu.containerId, 4),
            "重开背包不需要Open事件或重新点火，下一快照恢复门面");
    }

    @Test
    void containerDataPhaseDoesNotSendBeforeWriteback() {
        Fixture f = new Fixture();
        new LivingPortalFunction().tickContainerData(List.of(), f.ctx, new TickContext(f.ctx));
        verifyNoInteractions(f.viewer.connection, f.foreignViewer.connection);
    }

    @Test
    void closingMenuClearsSentSnapshotAndPacketCanRemoveClientDoor() {
        Fixture f = new Fixture();
        PortalDataAccess.persist(f.ctx, door());
        PortalServerSync.flush(f.ctx, new TickContext(f.ctx));
        var menu = f.viewer.containerMenu;
        PortalServerSync.onClose(new PlayerContainerEvent.Close(f.viewer, menu));
        ArgumentCaptor<PortalSnapshotPacket> sent = ArgumentCaptor.forClass(PortalSnapshotPacket.class);
        verify(f.viewer.connection, times(2)).send(sent.capture());
        PortalClientCache.update(menu.containerId, sent.getAllValues().get(0));
        assertTrue(PortalClientCache.contains(menu.containerId, 4));
        PortalSnapshotPacket cleared = sent.getAllValues().get(1);
        assertTrue(cleared.cells().isEmpty());
        assertTrue(cleared.menuSlots().isEmpty());
        PortalClientCache.update(menu.containerId, cleared);
        assertFalse(PortalClientCache.contains(menu.containerId, 4));
    }
}
