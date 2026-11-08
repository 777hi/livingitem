package com.qiqi.li.living.container;

import com.qiqi.li.client.util.ClientMenuSlotOwners;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 我方 Provider、范围写与混合槽契约；不模拟第三方实体行为。 */
class EntityContainerProviderTest {
    private static final BlockPos POS = new BlockPos(13, 64, 27);

    private static final class Menu extends AbstractContainerMenu {
        Menu() { super(MenuType.GENERIC_9x3, 1); }
        void append(Slot slot) { addSlot(slot); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    private static final class Host {
        final Entity entity = mock(Entity.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ItemStackHandler storage = new ItemStackHandler(36) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return !stack.is(Items.DIRT);
            }
        };
        final ItemStackHandler equipment = new ItemStackHandler(4);
        final Menu menu = new Menu();
        final EntityContainerContext context;
        final int size;

        Host(int size) {
            this.size = size;
            when(entity.getUUID()).thenReturn(UUID.randomUUID());
            when(entity.blockPosition()).thenReturn(POS);
            when(entity.level()).thenReturn(level);
            context = new EntityContainerContext(entity, level,
                new CombinedInvWrapper(new RangedWrapper(storage, 0, size))) {
                @Override public int getLiveColumns() { return 6; }
            };
            menu.append(new SlotItemHandler(equipment, 0, 0, 0));
            for (int i = 0; i < size; i++) {
                menu.append(new SlotItemHandler(storage, i, 20 + i * 18, 20));
            }
            menu.append(new SlotItemHandler(storage, 5, 20, 20)); // 同物理槽别名
            menu.append(new SlotItemHandler(storage, size, 20, 20)); // 可用前缀之外
            EntityContainers.registerProvider(new EntityContainers.Provider() {
                @Override public boolean supports(Entity value) { return value == entity; }
                @Override public EntityContainerContext createContext(Entity value, ServerLevel world) {
                    return context;
                }
                @Override public int menuPhysicalSlot(Entity value, AbstractContainerMenu current, Slot slot) {
                    if (current != menu || !(slot instanceof SlotItemHandler s)
                            || s.getItemHandler() != storage) return -1;
                    int index = slot.getContainerSlot();
                    return index >= 0 && index < size ? index : -1;
                }
            });
            MenuSlotOwners.registerEntityResolver((current, slot) -> current == menu ? entity : null);
        }
    }

    @Test
    void providerAdmissionAndFactoryUseSelectedStorageView() {
        Host host = new Host(6);
        assertTrue(EntityContainers.isContainerEntity(host.entity));
        assertSame(host.context, EntityContainers.createContext(host.entity, host.level));
        assertEquals(6, host.context.getSize());
        assertEquals(6, host.context.getWidth());
        assertEquals(ContainerLayout.Source.LIVE, host.context.getLayout().source());
        assertEquals("entity_" + host.entity.getUUID(), host.context.getContainerKey());
        assertSame(host.entity, host.context.getEntity());
        assertEquals(List.of(host.entity), host.context.getDataHosts());
        assertNull(host.context.getBlockPos());
        assertNull(EntityContainers.createContext(mock(Entity.class), host.level));
    }

    @Test
    void menuStorageOwnershipIsSharedByServerAndClient() {
        Host host = new Host(6);
        Slot equipment = host.menu.slots.getFirst();
        Slot storage = host.menu.slots.get(1);
        Slot hidden = host.menu.slots.getLast();
        assertSame(host.entity, EntityContainers.menuHostEntity(host.menu, equipment));
        assertNull(EntityContainers.resolveMenuEntity(host.menu, equipment));
        assertEquals(-1, ClientMenuSlotOwners.resolveStorageSlot(host.menu, equipment));
        assertTrue(MenuSlotOwners.resolve(host.menu, equipment).isEmpty());
        assertSame(host.entity, EntityContainers.resolveMenuEntity(host.menu, storage));
        assertEquals(0, ClientMenuSlotOwners.resolveStorageSlot(host.menu, storage));
        assertEquals(List.of(POS), MenuSlotOwners.resolve(host.menu, storage));
        assertEquals(-1, ClientMenuSlotOwners.resolveStorageSlot(host.menu, hidden));
        assertTrue(MenuSlotOwners.resolve(host.menu, hidden).isEmpty());
        Slot negative = new SlotItemHandler(host.storage, -1, 0, 0);
        assertEquals(-1, ClientMenuSlotOwners.resolveStorageSlot(host.menu, negative));
    }

    @Test
    void nonStorageEntitySlotCannotFallBackToBlockAtEntityPosition() {
        Host host = new Host(6);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(host.level);
        player.containerMenu = host.menu;
        assertSame(host.context, ContainerContexts.resolve(player, host.menu.slots.get(1)));
        assertNull(ContainerContexts.resolve(player, host.menu.slots.getFirst()));
        assertNull(ContainerContexts.resolve(player, host.menu.slots.getLast()));
        verify(host.level, never()).getBlockEntity(POS);
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 12, 18, 24, 36})
    void injectedRangeRejectsNegativeHiddenAndOverflowAcrossAllChannels(int size) {
        Host host = new Host(size);
        host.storage.setStackInSlot(0, new ItemStack(Items.APPLE));
        for (int index : new int[]{-1, Integer.MIN_VALUE, size, Integer.MAX_VALUE}) {
            assertTrue(host.context.getItem(index).isEmpty());
            assertEquals(-1, host.context.toPhysicalSlot(index));
            assertEquals(-1, host.context.toLogicalSlot(index));
            assertEquals(0, host.context.getSlotLimit(index));
            assertFalse(host.context.isItemValid(index, new ItemStack(Items.APPLE)));
            assertEquals(0, host.context.simulateInsertItem(index, new ItemStack(Items.APPLE)));
            assertEquals(0, host.context.simulateExtractItem(index, 1));
            assertFalse(host.context.supportsExactWrite(index));
            assertFalse(host.context.trySetItemExact(index, new ItemStack(Items.DIAMOND)));
            host.context.setItem(index, new ItemStack(Items.DIAMOND));
        }
        assertEquals(Items.APPLE, host.storage.getStackInSlot(0).getItem());
        assertEquals(0, host.context.simulateInsertItem(size - 1, new ItemStack(Items.DIRT)));
        assertFalse(host.context.isItemValid(size - 1, new ItemStack(Items.DIRT)));
        assertTrue(host.context.trySetItemExact(size - 1, new ItemStack(Items.DIAMOND)));
        assertEquals(Items.DIAMOND, host.storage.getStackInSlot(size - 1).getItem());
        for (int slot = 0; slot < size; slot++) {
            assertEquals(slot, host.context.toLogicalSlot(host.context.toPhysicalSlot(slot)));
        }
    }

    @Test
    void entityWholeMenuFastPathStillSynchronizesOnlyStorageAndAllAliases() {
        Host host = new Host(6);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer viewer = mock(ServerPlayer.class);
        viewer.connection = mock(ServerGamePacketListenerImpl.class);
        viewer.containerMenu = host.menu;
        when(host.level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of(viewer));
        assertEquals(POS, MenuSlotOwners.menuPosition(host.menu));
        host.context.syncSlotToClients(0, new ItemStack(Items.DIAMOND));
        assertTrue(host.menu.remoteSlots.get(0).isEmpty(), "同号装备槽不得接收存储格内容");
        assertEquals(Items.DIAMOND, host.menu.remoteSlots.get(1).getItem());
        host.context.syncSlotToClients(5, new ItemStack(Items.APPLE));
        assertEquals(Items.APPLE, host.menu.remoteSlots.get(6).getItem());
        assertEquals(Items.APPLE, host.menu.remoteSlots.get(7).getItem());
        ArgumentCaptor<ClientboundContainerSetSlotPacket> packets =
            ArgumentCaptor.forClass(ClientboundContainerSetSlotPacket.class);
        verify(viewer.connection, times(3)).send(packets.capture());
        assertEquals(List.of(1, 6, 7), packets.getAllValues().stream()
            .map(ClientboundContainerSetSlotPacket::getSlot).toList());
    }
}
