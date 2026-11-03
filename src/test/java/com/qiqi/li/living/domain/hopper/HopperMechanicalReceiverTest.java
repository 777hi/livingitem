package com.qiqi.li.living.domain.hopper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.ender.EnderChannelKey;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.transfer.MechanicalReceivers;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HopperMechanicalReceiverTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearRoutes() {
        EnderChannelRegistry.getInstance().clearAll();
    }

    private static ItemStack hopper(int count, Pos2D source, Pos2D target) {
        ItemStack stack = new ItemStack(Items.HOPPER, count);
        LivingItemManager.setLiving(stack, true);
        LivingHopperData.set(stack, LivingHopperData.DEFAULT.withDirection(new DirectionTransferData(source, target)));
        return stack;
    }

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack enderChest(int count, boolean direct) {
        ItemStack stack = living(new ItemStack(Items.ENDER_CHEST, count));
        if (direct) LivingEnderChestFunction.setBoundPlayer(stack, PLAYER, "player");
        return stack;
    }

    private record EnderSetup(Level level, PlayerEnderChestContainer inventory) {}

    private static EnderSetup enderSetup(boolean online) {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        PlayerEnderChestContainer inventory = new PlayerEnderChestContainer();
        Level level = mock(Level.class);
        org.mockito.Mockito.when(level.getServer()).thenReturn(server);
        org.mockito.Mockito.when(server.getPlayerList()).thenReturn(playerList);
        org.mockito.Mockito.when(playerList.getPlayer(PLAYER)).thenReturn(online ? player : null);
        if (online) org.mockito.Mockito.when(player.getEnderChestInventory()).thenReturn(inventory);
        return new EnderSetup(level, inventory);
    }

    private static class Ctx extends FakeContainerContext {
        Ctx() { super(9, 9, "mechanical_hopper"); }
        @Override public boolean supportsExactWrite(int slot) { return true; }
        @Override public int simulateExtractItem(int slot, int amount) {
            return Math.min(amount, getItem(slot).getCount());
        }
        @Override public int getSlotLimit(int slot) { return slot == 3 ? 2 : 64; }
        @Override public int simulateInsertItem(int slot, ItemStack stack) {
            return getItem(slot).isEmpty() ? Math.min(getSlotLimit(slot), stack.getCount()) : 0;
        }
    }

    private static int receive(Ctx ctx, TickContext tick) {
        Level level = mock(Level.class);
        org.mockito.Mockito.when(level.getServer()).thenReturn(mock(MinecraftServer.class));
        return receive(ctx, tick, level);
    }

    private static int receive(Ctx ctx, TickContext tick, Level level) {
        return MechanicalReceivers.receive(ctx, level, tick, 1, 2, Pos2D.RIGHT);
    }

    @Test
    void quantityIsMinOfCargoHopperAndTargetCapacity() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 7));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        TickContext tick = new TickContext(ctx);
        assertEquals(2, receive(ctx, tick));
        assertEquals(5, ctx.getItem(1).getCount());
        assertEquals(2, ctx.getItem(3).getCount());
    }

    @Test
    void wrongInputFaceRejectsWithoutMovingOrPushingReceiver() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 7));
        ItemStack receiver = hopper(3, Pos2D.UP, Pos2D.RIGHT);
        ctx.set(2, receiver);
        assertEquals(0, receive(ctx, new TickContext(ctx)));
        assertEquals(7, ctx.getItem(1).getCount());
        assertSame(receiver, ctx.getItem(2));
        assertTrue(ctx.getItem(3).isEmpty());
    }

    @Test
    void receiverLockAndCooldownAreNotChangedOrConsulted() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 2));
        ItemStack receiver = hopper(3, Pos2D.LEFT, Pos2D.RIGHT);
        LivingHopperData before = LivingHopperData.of(receiver)
            .withTransfer(new TransferData(99)).withDisabled(true);
        LivingHopperData.set(receiver, before);
        ctx.set(2, receiver);
        assertEquals(2, receive(ctx, new TickContext(ctx)));
        assertEquals(before, LivingHopperData.of(receiver));
    }

    @Test
    void blacklistFiltersCargoBody() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 3));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        FilterData denied = new FilterData(
            List.of(BuiltInRegistries.ITEM.getKey(Items.DIAMOND).toString()), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        TickContext tick = mock(TickContext.class);
        ContainerSnapshot snapshot = mock(ContainerSnapshot.class);
        org.mockito.Mockito.when(tick.getSnapshot()).thenReturn(snapshot);
        org.mockito.Mockito.when(snapshot.getFilterOf(2)).thenReturn(denied);
        assertEquals(0, receive(ctx, tick));
        assertEquals(3, ctx.getItem(1).getCount());
    }

    @Test
    void livingContainerCargoIsTransportedAsBodyNotContents() {
        Ctx ctx = new Ctx();
        ItemStack chest = living(new ItemStack(Items.CHEST, 1));
        chest.set(net.minecraft.core.component.DataComponents.CONTAINER,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(
                List.of(new ItemStack(Items.DIAMOND, 12))));
        ctx.set(1, chest);
        ctx.set(2, hopper(1, Pos2D.LEFT, Pos2D.RIGHT));
        assertEquals(1, receive(ctx, new TickContext(ctx)));
        assertTrue(ctx.getItem(1).isEmpty());
        assertTrue(ItemStack.isSameItemSameComponents(chest, ctx.getItem(3)));
    }

    @Test
    void targetLivingHopperDoesNotBecomeSecondLogisticsPipe() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 3));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        ItemStack target = hopper(2, Pos2D.LEFT, Pos2D.RIGHT);
        ctx.set(3, target);
        assertEquals(0, receive(ctx, new TickContext(ctx)));
        assertEquals(3, ctx.getItem(1).getCount());
        assertSame(target, ctx.getItem(3));
    }

    @Test
    void directEnderChestReceivesActualQuantity() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 7));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(3, enderChest(1, true));
        EnderSetup setup = enderSetup(true);

        assertEquals(3, receive(ctx, new TickContext(ctx), setup.level()));
        assertEquals(4, ctx.getItem(1).getCount());
        assertEquals(3, setup.inventory().getItem(0).getCount());
        assertTrue(EnderChannelRegistry.getInstance().peek(EnderChannelKey.playerChannel(PLAYER, 1)) == null);
    }

    @Test
    void directEnderChestOfflineRejectsWithoutConsumingCargo() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 7));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(3, enderChest(1, true));
        EnderSetup setup = enderSetup(false);

        assertEquals(0, receive(ctx, new TickContext(ctx), setup.level()));
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(setup.inventory().isEmpty());
    }

    @Test
    void directEnderChestPartialCapacityReturnsActualQuantity() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 7));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(3, enderChest(1, true));
        EnderSetup setup = enderSetup(true);
        for (int i = 0; i < setup.inventory().getContainerSize(); i++) {
            setup.inventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        setup.inventory().setItem(0, new ItemStack(Items.DIAMOND, 62));

        assertEquals(2, receive(ctx, new TickContext(ctx), setup.level()));
        assertEquals(5, ctx.getItem(1).getCount());
        assertEquals(64, setup.inventory().getItem(0).getCount());
    }

    @Test
    void routeEnderChestRejectsWithoutRegisteringOrMarkingTarget() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 7));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.RIGHT));
        ctx.set(3, enderChest(2, false));
        EnderSetup setup = enderSetup(true);
        EnderChannelKey key = EnderChannelKey.publicChannel(2);

        TickContext tick = new TickContext(ctx);
        assertEquals(0, receive(ctx, tick, setup.level()));
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(tick.transferredTargetSlots.isEmpty());
        assertTrue(EnderChannelRegistry.getInstance().peek(key) == null);
    }

    @Test
    void outputBackIntoSourceIsRejected() {
        Ctx ctx = new Ctx();
        ctx.set(1, new ItemStack(Items.DIAMOND, 3));
        ctx.set(2, hopper(3, Pos2D.LEFT, Pos2D.LEFT));
        assertEquals(0, receive(ctx, new TickContext(ctx)));
        assertEquals(3, ctx.getItem(1).getCount());
    }
}
