package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.FluidPresence;
import com.qiqi.li.living.api.FluidSourceLedger;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerDataSavedData;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;

/** 门主控的整区熄灭、流体两层存在性与持久清理后不复燃守卫。 */
class LivingPortalFunctionTest {

    private Function<TickContext, FluidPresence> previousPresence;
    private Function<TickContext, FluidSourceLedger> previousLedger;
    private ContainerFluidData fluids;
    private Context ctx;

    private static final class Context extends FakeContainerContext {
        final UUID uuid = UUID.randomUUID();
        final ServerLevel level = mock(ServerLevel.class);
        boolean positioned = true;

        Context(int width, int height) {
            super(width * height, width, "portal_test_" + UUID.randomUUID());
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            withBlockPos(new BlockPos(4, 64, 8));
            for (int slot = 0; slot < getSize(); slot++) {
                int row = slot / width;
                int col = slot % width;
                if (row == 0 || row == height - 1 || col == 0 || col == width - 1) rawSet(slot, living(Items.OBSIDIAN));
            }
        }
        @Override public Level getLevel() { return level; }
        @Override public UUID getContainerUuid() { return uuid; }
        @Override public ContainerAnchor getAnchor() {
            return positioned ? new ContainerAnchor.AtBlock(getBlockPos()) : null;
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void isolateFluidResolvers() throws ReflectiveOperationException {
        Field presence = TickContext.class.getDeclaredField("fluidPresenceResolver");
        Field ledger = TickContext.class.getDeclaredField("sourceLedgerResolver");
        presence.setAccessible(true);
        ledger.setAccessible(true);
        previousPresence = (Function<TickContext, FluidPresence>) presence.get(null);
        previousLedger = (Function<TickContext, FluidSourceLedger>) ledger.get(null);
        fluids = new ContainerFluidData();
        TickContext.registerFluidPresenceResolver(tick -> fluids);
        TickContext.registerSourceLedgerResolver(tick -> fluids);
    }

    @AfterEach
    void restoreResolversAndCaches() {
        TickContext.registerFluidPresenceResolver(previousPresence);
        TickContext.registerSourceLedgerResolver(previousLedger);
        if (ctx != null) ContainerDataSavedData.get().remove(ctx.uuid);
        ContainerLivingItemHandler.clearAllCaches();
    }

    private static ItemStack living(net.minecraft.world.item.Item item) {
        ItemStack stack = new ItemStack(item);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private void ignite(int width, Set<Integer> cells) {
        ctx = new Context(width, 3);
        PortalDataAccess.persist(ctx, ContainerPortalData.EMPTY.withRegion(width, cells));
    }

    @Test
    void ordinaryCargoDoesNotExtinguishAndMaintenanceDoesNotConsumeIt() {
        ignite(5, Set.of(6, 7, 8));
        ItemStack cargo = new ItemStack(Items.DIAMOND, 500);
        ctx.rawSet(7, cargo);
        ContainerPortalData maintained = LivingPortalFunction.maintain(ctx, new TickContext(ctx));
        assertEquals(Set.of(6, 7, 8), maintained.portalSlots());
        assertSame(cargo, ctx.getItem(7));
        assertEquals(500, ctx.getItem(7).getCount());
        assertEquals(maintained, ContainerDataSavedData.get().get(ctx.uuid, ContainerPortalData.KEY));
    }

    @Test
    void actualFluidWithoutLedgerExtinguishesWholeDoorButKeepsCargo() {
        ignite(5, Set.of(6, 7, 8));
        ctx.rawSet(8, new ItemStack(Items.DIAMOND, 32));
        fluids.getFlows().put(6, new ContainerFluidData.FlowEntry(4, false, 1, Fluids.WATER.getFluidType()));
        assertTrue(fluids.hasFluidAt(6));
        assertNull(fluids.sourceAt(6), "只有实际流动层，没有即时源账本");
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
        assertEquals(32, ctx.getItem(8).getCount());
        assertNull(ContainerDataSavedData.get().get(ctx.uuid, ContainerPortalData.KEY));
    }

    @Test
    void immediateSourceLedgerBeforeActualFlowExtinguishesWholeDoor() {
        ignite(5, Set.of(6, 7, 8));
        fluids.registerGeneratedSource(7, Fluids.WATER.getFluidType());
        assertFalse(fluids.hasFluidAt(7), "实际流动层尚未更新");
        assertNotNull(fluids.sourceAt(7));
        assertTrue(LivingPortalFunction.fluidAt(new TickContext(ctx)).test(7));
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
    }

    @Test
    void arbitraryLivingInteriorItemIncludingFrameExtinguishesEntireRegion() {
        for (var item : List.of(Items.STONE, Items.OBSIDIAN)) {
            ignite(5, Set.of(6, 7, 8));
            ctx.rawSet(7, living(item));
            ctx.rawSet(8, new ItemStack(Items.GOLD_INGOT, 2));
            assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
            assertTrue(LivingItemManager.isLivingItem(ctx.getItem(7)));
            assertEquals(2, ctx.getItem(8).getCount());
            ContainerDataSavedData.get().remove(ctx.uuid);
        }
    }

    @Test
    void noPositionClearsOnlyPortalStateAndReturningPositionDoesNotReignite() {
        ignite(5, Set.of(6, 7, 8));
        ctx.rawSet(8, new ItemStack(Items.DIAMOND, 64));
        ctx.positioned = false;
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
        assertEquals(64, ctx.getItem(8).getCount());
        ctx.positioned = true;
        ContainerLivingItemHandler.removeContainerData(ctx, ContainerPortalData.KEY);
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
    }

    @Test
    void brokenBoundaryRepairAndReloadDoNotReignite() {
        ignite(5, Set.of(6, 7, 8));
        ctx.rawSet(2, ItemStack.EMPTY);
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
        ctx.rawSet(2, living(Items.OBSIDIAN));
        ContainerLivingItemHandler.removeContainerData(ctx, ContainerPortalData.KEY);
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty());
    }

    @Test
    void independentSharedFrameDoorsClearLocallyUntilSharedFrameBreaks() {
        ctx = new Context(5, 3);
        ctx.rawSet(7, living(Items.OBSIDIAN));
        PortalDataAccess.persist(ctx, ContainerPortalData.EMPTY.withRegion(5, Set.of(6)).withRegion(5, Set.of(8)));
        fluids.registerGeneratedSource(6, Fluids.WATER.getFluidType());
        ContainerPortalData after = LivingPortalFunction.maintain(ctx, new TickContext(ctx));
        assertEquals(Set.of(8), after.portalSlots(), "左门灭不牵连右门");
        fluids.clearAll();
        assertEquals(Set.of(8), LivingPortalFunction.maintain(ctx, new TickContext(ctx)).portalSlots());
        ctx.rawSet(7, ItemStack.EMPTY);
        assertTrue(LivingPortalFunction.maintain(ctx, new TickContext(ctx)).isEmpty(), "必要共享框拆掉使剩余门灭");
    }

    @Test
    void tickExtinguishesBeforeCargoTransferAndCannotSendEarlierRegionCells() {
        ignite(5, Set.of(6, 7, 8));
        ctx.rawSet(6, new ItemStack(Items.DIAMOND, 32));
        fluids.registerGeneratedSource(8, Fluids.WATER.getFluidType());
        new LivingPortalFunction().tickContainerData(List.of(), ctx, new TickContext(ctx));
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
        assertEquals(32, ctx.getItem(6).getCount());
        verify(ctx.level, never()).addFreshEntity(any());
    }

    @Test
    void persistedValidDoorRestoresButClearDoesNotRestoreOldCopy() {
        ignite(5, Set.of(6, 7, 8));
        ContainerLivingItemHandler.removeContainerData(ctx, ContainerPortalData.KEY);
        assertEquals(Set.of(6, 7, 8), PortalDataAccess.getOrLoad(ctx).portalSlots());
        PortalDataAccess.clear(ctx);
        ContainerLivingItemHandler.removeContainerData(ctx, ContainerPortalData.KEY);
        assertTrue(PortalDataAccess.getOrLoad(ctx).isEmpty());
    }
}
