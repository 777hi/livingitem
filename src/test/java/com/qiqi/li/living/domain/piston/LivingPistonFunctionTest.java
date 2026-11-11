package com.qiqi.li.living.domain.piston;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.container.MoveResult;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegmentType;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LivingPistonFunctionTest {

    private final LivingPistonFunction function = new LivingPistonFunction();
    private final Level level = mock(Level.class);
    private final List<FakeContainerContext> contexts = new ArrayList<>();

    @BeforeEach
    void drainEarlierDirtyState() {
        ContainerRuntimeCache.drainDirty();
    }

    @AfterEach
    void cleanOwnContexts() {
        for (var ctx : contexts) ContainerRuntimeCache.removeContainer(ctx.getCacheKey());
    }

    private FakeContainerContext context() {
        return track(new FakeContainerContext(9, 9, "piston_function_" + contexts.size()));
    }

    private <T extends FakeContainerContext> T track(T ctx) {
        contexts.add(ctx);
        return ctx;
    }

    private static ItemStack piston(Item item, int count, Pos2D direction) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        LivingPistonData.set(stack, new LivingPistonData(direction));
        return stack;
    }

    private static PistonRuntime state(FakeContainerContext ctx, int slot) {
        return ContainerRuntimeCache.get(ctx, slot).get(PistonSegment.INSTANCE);
    }

    private void tick(FakeContainerContext ctx, int signal, int... slots) {
        List<SlotEntry> entries = new ArrayList<>();
        for (int slot : slots) entries.add(new SlotEntry(slot, ctx.getItem(slot)));
        function.tick(entries, ctx, tickContext(ctx, signal), level);
    }

    private static TickContext tickContext(FakeContainerContext ctx, int signal) {
        RedstoneSensor sensor = mock(RedstoneSensor.class);
        when(sensor.maxSensedSignal(anyInt())).thenReturn(signal);
        TickContext tick = mock(TickContext.class);
        when(tick.getSensor(ctx)).thenReturn(sensor);
        return tick;
    }

    @Test
    void recognizesBothLivingPistonsOnly() {
        assertTrue(function.canApply(piston(Items.PISTON, 1, Pos2D.UP)));
        assertTrue(function.canApply(piston(Items.STICKY_PISTON, 1, Pos2D.UP)));
        assertFalse(function.canApply(new ItemStack(Items.PISTON)));
        assertFalse(function.canApply(new ItemStack(Items.STICKY_PISTON)));
        assertFalse(function.canApply(piston(Items.HOPPER, 1, Pos2D.UP)));
        assertFalse(function.canApply(ItemStack.EMPTY));
    }

    @Test
    void observableStateMatchesDirectionAndExtensionOnly() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT));

        var retracted = function.getObservableState(ctx.getItem(0), 0, ctx);
        assertEquals(1, retracted.getInt("direction_x"));
        assertEquals(0, retracted.getInt("direction_y"));
        assertFalse(retracted.getBoolean("extended"));
        assertEquals(3, retracted.size(),
            "公开状态只含方向与 extended，不暴露信号、动画或动作历史");

        tick(ctx, 15, 0);
        var extended = function.getObservableState(ctx.getItem(0), 0, ctx);
        assertTrue(extended.getBoolean("extended"));

        tick(ctx, 0, 0);
        var retractedAgain = function.getObservableState(ctx.getItem(0), 0, ctx);
        assertFalse(retractedAgain.getBoolean("extended"));
    }

    @Test
    void realFunctionPushesChainThenHoldsWithoutRewritingRuntime() {
        var ctx = context().set(0, piston(Items.PISTON, 2, Pos2D.RIGHT))
            .set(1, new ItemStack(Items.DIRT, 7)).set(2, new ItemStack(Items.GOLD_INGOT, 3));
        tick(ctx, 15, 0);
        assertTrue(state(ctx, 0).extended());
        assertTrue(ctx.getItem(1).isEmpty());
        assertEquals(7, ctx.getItem(2).getCount());
        assertEquals(3, ctx.getItem(3).getCount());
        var segments = ContainerRuntimeCache.get(ctx, 0);
        assertTrue(ContainerRuntimeCache.drainDirty().containsKey(ctx.getCacheKey()));
        ctx.syncedSlots.clear();

        tick(ctx, 15, 0);
        assertSame(segments, ContainerRuntimeCache.get(ctx, 0));
        assertTrue(ctx.syncedSlots.isEmpty());
        assertFalse(ContainerRuntimeCache.drainDirty().containsKey(ctx.getCacheKey()));
    }

    @Test
    void emptyPushExtendsAndOrdinaryPowerOffDoesNotPull() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT));
        tick(ctx, 1, 0);
        assertTrue(state(ctx, 0).extended());
        ctx.set(2, new ItemStack(Items.DIRT, 4));
        tick(ctx, 0, 0);
        assertFalse(state(ctx, 0).extended());
        assertTrue(ctx.getItem(1).isEmpty());
        assertEquals(4, ctx.getItem(2).getCount());
    }

    @Test
    void blockedChainRetriesWithNewForceWithoutNewSignalEdge() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT))
            .set(1, new ItemStack(Items.DIRT)).set(2, new ItemStack(Items.GOLD_INGOT));
        tick(ctx, 15, 0);
        assertFalse(state(ctx, 0).extended());
        ctx.getItem(0).setCount(2);
        tick(ctx, 15, 0);
        assertTrue(state(ctx, 0).extended());
        assertTrue(ctx.getItem(1).isEmpty());
        assertEquals(Items.GOLD_INGOT, ctx.getItem(3).getItem());
    }

    @Test
    void changingCountDoesNotResetExistingExtension() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT));
        tick(ctx, 15, 0);
        ctx.getItem(0).setCount(3);
        ctx.set(1, new ItemStack(Items.DIRT, 7));
        tick(ctx, 15, 0);
        assertTrue(state(ctx, 0).extended());
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(ctx.getItem(2).isEmpty());
    }

    @Test
    void stickyPowerOffPullsOneStackAndRetracts() {
        var ctx = context().set(0, piston(Items.STICKY_PISTON, 1, Pos2D.RIGHT))
            .set(1, new ItemStack(Items.DIRT, 7));
        tick(ctx, 15, 0);
        tick(ctx, 0, 0);
        assertFalse(state(ctx, 0).extended());
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(ctx.getItem(2).isEmpty());
    }

    @Test
    void stickyPullFailureStillRetracts() {
        var ctx = context().set(0, piston(Items.STICKY_PISTON, 1, Pos2D.RIGHT))
            .set(1, new ItemStack(Items.DIRT, 7));
        tick(ctx, 15, 0);
        ctx.set(1, new ItemStack(Items.GOLD_INGOT));
        tick(ctx, 0, 0);
        assertFalse(state(ctx, 0).extended());
        assertEquals(7, ctx.getItem(2).getCount());
    }

    @Test
    void changingDirectionWhileUnpoweredDoesNotPullEitherDirection() {
        var ctx = track(new FakeContainerContext(25, 5, "piston_direction_change"))
            .set(12, piston(Items.STICKY_PISTON, 1, Pos2D.RIGHT));
        tick(ctx, 15, 12);
        ctx.set(14, new ItemStack(Items.DIRT, 7)).set(2, new ItemStack(Items.GOLD_INGOT, 3));
        assertTrue(function.updateSlotDirection(ctx.getItem(12), "direction", Pos2D.UP));
        tick(ctx, 0, 12);
        assertEquals(PistonRuntime.retracted(Pos2D.UP), state(ctx, 12));
        assertEquals(7, ctx.getItem(14).getCount());
        assertEquals(3, ctx.getItem(2).getCount());
        assertTrue(ctx.getItem(7).isEmpty());
        assertTrue(ctx.getItem(13).isEmpty());
    }

    @Test
    void changingDirectionWhilePoweredCanStartNewAction() {
        var ctx = track(new FakeContainerContext(25, 5, "piston_direction_powered"))
            .set(12, piston(Items.PISTON, 1, Pos2D.RIGHT));
        tick(ctx, 15, 12);
        ctx.set(7, new ItemStack(Items.DIRT, 7));
        function.updateSlotDirection(ctx.getItem(12), "direction", Pos2D.UP);
        tick(ctx, 15, 12);
        assertEquals(new PistonRuntime(true, Pos2D.UP), state(ctx, 12));
        assertEquals(7, ctx.getItem(2).getCount());
        assertTrue(ctx.getItem(7).isEmpty());
    }

    @Test
    void staleEntryUsesCurrentDirectionAndCount() {
        var ctx = track(new FakeContainerContext(25, 5, "piston_current_stack"));
        var old = piston(Items.PISTON, 1, Pos2D.RIGHT);
        var entries = List.of(new SlotEntry(17, old));
        ctx.set(17, piston(Items.PISTON, 2, Pos2D.UP))
            .set(12, new ItemStack(Items.DIRT)).set(7, new ItemStack(Items.GOLD_INGOT));
        function.tick(entries, ctx, tickContext(ctx, 15), level);
        assertEquals(new PistonRuntime(true, Pos2D.UP), state(ctx, 17));
        assertTrue(ctx.getItem(12).isEmpty());
        assertEquals(Items.DIRT, ctx.getItem(7).getItem());
        assertEquals(Items.GOLD_INGOT, ctx.getItem(2).getItem());
        assertTrue(ctx.getItem(18).isEmpty());
    }

    @Test
    void replacedOrDeactivatedEntryIsSkipped() {
        var ctx = context();
        var old = piston(Items.PISTON, 1, Pos2D.RIGHT);
        ctx.set(0, new ItemStack(Items.PISTON)).set(1, new ItemStack(Items.DIRT));
        function.tick(List.of(new SlotEntry(0, old), new SlotEntry(-1, old), new SlotEntry(99, old)),
            ctx, tickContext(ctx, 15), level);
        assertNull(state(ctx, 0));
        assertEquals(Items.DIRT, ctx.getItem(1).getItem());
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void clientSideNeverReadsOrMovesServerState() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT))
            .set(1, new ItemStack(Items.DIRT));
        Level client = mock(Level.class);
        when(client.isClientSide()).thenReturn(true);
        TickContext tick = mock(TickContext.class);
        function.tick(List.of(new SlotEntry(0, ctx.getItem(0))), ctx, tick, client);
        verifyNoInteractions(tick);
        assertNull(state(ctx, 0));
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void unavailableSensorDoesNotInventPowerOffOrPull() {
        var ctx = context().set(0, piston(Items.STICKY_PISTON, 1, Pos2D.RIGHT))
            .set(2, new ItemStack(Items.DIRT));
        ContainerRuntimeCache.update(ctx, 0,
            RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE, new PistonRuntime(true, Pos2D.RIGHT)));
        function.tick(List.of(new SlotEntry(0, ctx.getItem(0))), ctx, mock(TickContext.class), level);
        assertTrue(state(ctx, 0).extended());
        assertTrue(ctx.getItem(1).isEmpty());
    }

    @Test
    void receiverPendingNeverClaimsDeliveryOrMovesHopper() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT))
            .set(1, new ItemStack(Items.DIRT, 7));
        ItemStack hopper = new ItemStack(Items.HOPPER, 3);
        LivingItemManager.setLiving(hopper, true);
        ctx.set(2, hopper);
        tick(ctx, 15, 0);
        assertFalse(state(ctx, 0).extended());
        assertEquals(7, ctx.getItem(1).getCount());
        assertSame(hopper, ctx.getItem(2));
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void backendViolationCannotProduceExtendedSuccess() {
        var ctx = track(new FakeContainerContext(9, 9, "piston_backend_violation") {
            @Override public MoveResult moveStack(int from, int to) {
                return new MoveResult.BackendViolation("test backend violation");
            }
        }).set(0, piston(Items.PISTON, 1, Pos2D.RIGHT)).set(1, new ItemStack(Items.DIRT));
        tick(ctx, 15, 0);
        assertFalse(state(ctx, 0).extended());
        assertEquals(Items.DIRT, ctx.getItem(1).getItem());
    }

    @Test
    void runtimeReadAndWriteUseLogicalToPhysicalMapping() {
        var ctx = track(new FakeContainerContext(9, 9, "piston_offset_mapping") {
            @Override public int toPhysicalSlot(int slot) { return slot + 1; }
            @Override public int toLogicalSlot(int slot) { return slot - 1; }
        }).set(0, piston(Items.PISTON, 1, Pos2D.RIGHT));
        tick(ctx, 15, 0);
        var runtime = ContainerRuntimeCache.getSnapshot(ctx.getCacheKey());
        assertEquals(Map.of(1, ContainerRuntimeCache.get(ctx, 0)), runtime);
        assertTrue(state(ctx, 0).extended());
        ContainerRuntimeCache.drainDirty();
        tick(ctx, 15, 0);
        assertFalse(ContainerRuntimeCache.drainDirty().containsKey(ctx.getCacheKey()));
    }

    @Test
    void runtimeUpdatesPreserveOtherSegments() {
        var ctx = context().set(0, piston(Items.PISTON, 1, Pos2D.RIGHT));
        RuntimeSegmentType<Integer> other = new RuntimeSegmentType<>() {
            @Override public String id() { return "piston_test_other"; }
            @Override public StreamCodec<? super RegistryFriendlyByteBuf, Integer> codec() {
                return ByteBufCodecs.INT;
            }
        };
        ContainerRuntimeCache.update(ctx, 0, RuntimeSegments.EMPTY.with(other, 42));
        tick(ctx, 15, 0);
        assertEquals(Integer.valueOf(42), ContainerRuntimeCache.get(ctx, 0).get(other));
        assertTrue(state(ctx, 0).extended());
    }

    @Test
    void twoStickyPistonsUseUpdatedSourceAndBothRetract() {
        var ctx = context().set(0, piston(Items.STICKY_PISTON, 1, Pos2D.RIGHT))
            .set(4, piston(Items.STICKY_PISTON, 1, Pos2D.LEFT)).set(2, new ItemStack(Items.DIRT, 7));
        ContainerRuntimeCache.update(ctx, 0,
            RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE, new PistonRuntime(true, Pos2D.RIGHT)));
        ContainerRuntimeCache.update(ctx, 4,
            RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE, new PistonRuntime(true, Pos2D.LEFT)));
        tick(ctx, 0, 0, 4);
        assertFalse(state(ctx, 0).extended());
        assertFalse(state(ctx, 4).extended());
        assertEquals(7, ctx.getItem(1).getCount());
        assertTrue(ctx.getItem(2).isEmpty());
        assertTrue(ctx.getItem(3).isEmpty());
    }

    @Test
    void clearedRuntimeStartsFreshWithoutReplayingPull() {
        var ctx = context().set(0, piston(Items.STICKY_PISTON, 1, Pos2D.RIGHT))
            .set(2, new ItemStack(Items.DIRT));
        ContainerRuntimeCache.update(ctx, 0,
            RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE, new PistonRuntime(true, Pos2D.RIGHT)));
        ContainerRuntimeCache.removeContainer(ctx.getCacheKey());
        tick(ctx, 0, 0);
        assertFalse(state(ctx, 0).extended());
        assertTrue(ctx.getItem(1).isEmpty());
        tick(ctx, 15, 0);
        assertTrue(state(ctx, 0).extended());
    }
}
