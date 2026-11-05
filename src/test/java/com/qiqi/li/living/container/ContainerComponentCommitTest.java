package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 原数量组件提交的接缝契约：旧值重验、独立快照、精确写入、恢复与最终状态通知。
 * 网络发包及领域 tick 钩子不在本测试范围；脏槽与修订计数使用真实同步入口。
 */
class ContainerComponentCommitTest {

    private static class ReadOnlyHandler implements IItemHandler {
        final ItemStack[] slots;
        final boolean copyReads;
        int inserts;
        int extracts;

        ReadOnlyHandler(int size, boolean copyReads) {
            slots = new ItemStack[size];
            Arrays.fill(slots, ItemStack.EMPTY);
            this.copyReads = copyReads;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return copyReads ? slots[slot].copy() : slots[slot];
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            inserts++;
            throw new AssertionError("组件提交不得插入物品");
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            extracts++;
            throw new AssertionError("组件提交不得抽取物品");
        }
    }

    private static class WritableHandler extends ReadOnlyHandler implements IItemHandlerModifiable {
        int writes;
        int lastWriteSlot = -1;
        BiConsumer<Integer, ItemStack> writer = (slot, stack) -> slots[slot] = stack;

        WritableHandler(int size, boolean copyReads) {
            super(size, copyReads);
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            writes++;
            lastWriteSlot = slot;
            writer.accept(slot, stack);
        }
    }

    /** 保留真实 BE 通知，只记录同步的槽号与独立最终状态。 */
    private static class RecordingContext extends SimpleContainerContext {
        final List<Integer> syncedSlots = new ArrayList<>();
        final List<ItemStack> syncedStacks = new ArrayList<>();

        RecordingContext(IItemHandler handler) {
            super(handler);
        }

        RecordingContext(IItemHandler handler, List<BlockPos> positions, List<BlockEntity> entities) {
            super(handler, positions, entities);
        }

        @Override public void setItem(int slot, ItemStack stack) {
            throw new AssertionError("组件提交不得借用数量写入入口");
        }

        @Override public void syncSlotToClients(int slot, ItemStack stack) {
            syncedSlots.add(slot);
            syncedStacks.add(stack.copy());
        }
    }

    private static ItemStack named(ItemStack stack, String name) {
        ItemStack result = stack.copy();
        result.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return result;
    }

    private static ItemStack original(int count) {
        return named(new ItemStack(Items.STONE, count), "before");
    }

    private static WritableHandler handler(boolean copyReads) {
        WritableHandler handler = new WritableHandler(1, copyReads);
        handler.slots[0] = original(7);
        return handler;
    }

    private static void assertStack(ItemStack expected, ItemStack actual) {
        assertTrue(ItemStack.matches(expected, actual), "物品、数量与组件必须全部匹配");
    }

    private static void assertNoFallback(ReadOnlyHandler handler) {
        assertEquals(0, handler.inserts);
        assertEquals(0, handler.extracts);
    }

    @ParameterizedTest(name = "copyReads={0}: 原数量组件提交")
    @ValueSource(booleans = {false, true})
    void commitsWithLiveOrCopyReads(boolean copyReads) {
        WritableHandler handler = handler(copyReads);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0);
        ItemStack beforeSnapshot = before.copy();
        ItemStack after = named(before, "after");
        ItemStack afterSnapshot = after.copy();

        assertTrue(ctx.supportsItemComponentWrite(0));
        assertTrue(ctx.commitItemComponents(0, before, after));

        assertStack(afterSnapshot, handler.slots[0]);
        assertStack(beforeSnapshot, before);
        assertStack(afterSnapshot, after);
        assertNotSame(after, handler.slots[0]);
        assertEquals(1, handler.writes);
        assertEquals(List.of(0), ctx.syncedSlots);
        assertStack(afterSnapshot, ctx.syncedStacks.getFirst());
        assertNoFallback(handler);
    }

    private enum UnchangedFailure { RETURN_FALSE, SILENT_SETTER, UNSUPPORTED_SETTER }

    @ParameterizedTest
    @EnumSource(UnchangedFailure.class)
    void unchangedFailureDoesNotSyncOrFallBack(UnchangedFailure mode) {
        WritableHandler handler = handler(false);
        if (mode == UnchangedFailure.SILENT_SETTER) handler.writer = (slot, stack) -> {};
        if (mode == UnchangedFailure.UNSUPPORTED_SETTER) {
            handler.writer = (slot, stack) -> { throw new UnsupportedOperationException(); };
        }
        RecordingContext ctx = new RecordingContext(handler) {
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                return mode != UnchangedFailure.RETURN_FALSE && super.trySetItemExact(slot, stack);
            }
        };
        ItemStack before = ctx.getItem(0).copy();

        assertFalse(ctx.commitItemComponents(0, before, named(before, "after")));

        assertStack(before, handler.slots[0]);
        assertEquals(mode == UnchangedFailure.RETURN_FALSE ? 0 : 1, handler.writes);
        assertTrue(ctx.syncedSlots.isEmpty());
        assertNoFallback(handler);
    }

    @Test
    void staleBeforeRejectsEvenWhenRequestedAfterEqualsBefore() {
        WritableHandler handler = handler(false);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();
        ItemStack current = named(before, "concurrent-change");
        handler.slots[0] = current;

        assertFalse(ctx.commitItemComponents(0, before, named(before, "after")));
        assertFalse(ctx.commitItemComponents(0, before, before.copy()));

        assertStack(current, handler.slots[0]);
        assertEquals(0, handler.writes);
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void identicalCurrentSnapshotSucceedsWithoutWriteOrSync() {
        WritableHandler handler = handler(false);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();

        assertTrue(ctx.commitItemComponents(0, before, before.copy()));

        assertEquals(0, handler.writes);
        assertTrue(ctx.syncedSlots.isEmpty());
        assertStack(before, handler.slots[0]);
    }

    @Test
    void invalidSlotsAndNullSnapshotsRejectBeforeWriting() {
        WritableHandler handler = handler(false);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();
        ItemStack after = named(before, "after");

        assertFalse(ctx.supportsItemComponentWrite(-1));
        assertFalse(ctx.supportsItemComponentWrite(1));
        assertFalse(ctx.commitItemComponents(-1, before, after));
        assertFalse(ctx.commitItemComponents(1, before, after));
        assertFalse(ctx.commitItemComponents(0, null, after));
        assertFalse(ctx.commitItemComponents(0, before, null));

        assertEquals(0, handler.writes);
        assertTrue(ctx.syncedSlots.isEmpty());
        assertStack(before, handler.slots[0]);
    }

    @Test
    void emptyItemAndCountChangesAreNotComponentCommits() {
        WritableHandler handler = handler(false);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();

        assertFalse(ctx.commitItemComponents(0, ItemStack.EMPTY, before));
        assertFalse(ctx.commitItemComponents(0, before, ItemStack.EMPTY));
        assertFalse(ctx.commitItemComponents(0, before, new ItemStack(Items.DIRT, 7)));
        assertFalse(ctx.commitItemComponents(0, before, before.copyWithCount(6)));
        assertFalse(ctx.commitItemComponents(0, before, before.copyWithCount(8)));
        handler.slots[0] = ItemStack.EMPTY;
        assertFalse(ctx.commitItemComponents(0, ItemStack.EMPTY, ItemStack.EMPTY));

        assertEquals(0, handler.writes);
        assertTrue(ctx.syncedSlots.isEmpty());
        assertNoFallback(handler);
    }

    @Test
    void existingOversizedCountIsNotClampedToSlotLimit() {
        WritableHandler handler = handler(false);
        handler.slots[0] = original(2048);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();
        ItemStack after = named(before, "after");

        assertEquals(64, ctx.getSlotLimit(0));
        assertTrue(ctx.commitItemComponents(0, before, after));

        assertStack(after, handler.slots[0]);
        assertEquals(2048, handler.slots[0].getCount());
        assertEquals(1, handler.writes);
        assertNoFallback(handler);
    }

    @Test
    void defaultCommitProtectsExpectedSnapshotAndUsesDefaultSyncNotification() {
        List<ItemStack> writes = new ArrayList<>();
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            @Override public boolean supportsItemComponentWrite(int slot) { return isValidSlot(slot); }
            @Override public boolean trySetItemExact(int slot, ItemStack argument) {
                writes.add(argument.copy());
                set(slot, argument.copy());
                argument.setCount(1);
                argument.set(DataComponents.CUSTOM_NAME, Component.literal("hostile-argument"));
                return true;
            }
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("默认组件提交不得借用 setItem");
            }
        };
        ctx.set(0, original(7));
        ItemStack before = ctx.getItem(0).copy();
        ItemStack after = named(before, "after");
        ItemStack expected = after.copy();

        assertTrue(ctx.commitItemComponents(0, before, after));

        assertStack(expected, after);
        assertStack(expected, ctx.getItem(0));
        assertEquals(1, writes.size());
        assertStack(expected, writes.getFirst());
        assertEquals(List.of(0), ctx.syncedSlots);
    }

    @ParameterizedTest(name = "copyReads={0}: 活引用及 setter 参数损坏后恢复独立快照")
    @ValueSource(booleans = {false, true})
    void partialWriteRestoresSnapshotDespiteMutatingArgumentAndOldLiveReference(boolean copyReads) {
        WritableHandler handler = handler(copyReads);
        ItemStack oldLive = handler.slots[0];
        handler.writer = (slot, argument) -> {
            if (handler.writes == 1) {
                oldLive.setCount(1);
                oldLive.set(DataComponents.CUSTOM_NAME, Component.literal("damaged-old-live"));
                argument.setCount(2);
                argument.set(DataComponents.CUSTOM_NAME, Component.literal("partial"));
            }
            handler.slots[slot] = argument;
        };
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0);
        ItemStack originalSnapshot = before.copy();
        ItemStack after = named(before, "after");
        ItemStack afterSnapshot = after.copy();

        assertFalse(ctx.commitItemComponents(0, before, after));

        assertEquals(2, handler.writes);
        assertStack(originalSnapshot, handler.slots[0]);
        assertStack(afterSnapshot, after);
        assertNotSame(oldLive, handler.slots[0]);
        assertEquals(List.of(0), ctx.syncedSlots);
        assertStack(originalSnapshot, ctx.syncedStacks.getFirst());
        assertNoFallback(handler);
    }

    @Test
    void failedRestoreReportsFailureAndSyncsActualPartialState() {
        WritableHandler handler = handler(false);
        ItemStack partial = named(original(3), "partial");
        handler.writer = (slot, argument) -> {
            if (handler.writes == 1) handler.slots[slot] = partial.copy();
        };
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();

        assertFalse(ctx.commitItemComponents(0, before, named(before, "after")));

        assertEquals(2, handler.writes);
        assertStack(partial, handler.slots[0]);
        assertEquals(List.of(0), ctx.syncedSlots);
        assertStack(partial, ctx.syncedStacks.getFirst());
        assertNoFallback(handler);
    }

    @Test
    void matchingReadbackAloneCannotOverrideWriterFalse() {
        WritableHandler handler = handler(false);
        RecordingContext ctx = new RecordingContext(handler) {
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                super.trySetItemExact(slot, stack);
                return false;
            }
        };
        ItemStack before = ctx.getItem(0).copy();

        assertFalse(ctx.commitItemComponents(0, before, named(before, "after")));

        assertEquals(2, handler.writes);
        assertStack(before, handler.slots[0]);
        assertEquals(List.of(0), ctx.syncedSlots);
        assertStack(before, ctx.syncedStacks.getFirst());
        assertNoFallback(handler);
    }

    @Test
    void runtimeExceptionAfterPartialWriteStillRestoresAndSyncs() {
        WritableHandler handler = handler(false);
        handler.writer = (slot, argument) -> {
            if (handler.writes == 1) {
                handler.slots[slot] = argument.copyWithCount(2);
                throw new IllegalStateException("partial write");
            }
            handler.slots[slot] = argument;
        };
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();

        assertFalse(ctx.commitItemComponents(0, before, named(before, "after")));

        assertEquals(2, handler.writes);
        assertStack(before, handler.slots[0]);
        assertEquals(List.of(0), ctx.syncedSlots);
        assertStack(before, ctx.syncedStacks.getFirst());
        assertNoFallback(handler);
    }

    @Test
    void defaultContextDoesNotInheritComponentWriteFromQuantityWriteCapability() {
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("默认组件接缝不得借用 setItem");
            }
            @Override public int simulateInsertItem(int slot, ItemStack stack) {
                throw new AssertionError("默认组件接缝不得探测插入");
            }
            @Override public int simulateExtractItem(int slot, int amount) {
                throw new AssertionError("默认组件接缝不得探测抽取");
            }
        };
        ctx.set(0, original(7));
        ItemStack before = ctx.getItem(0).copy();

        assertTrue(ctx.supportsExactWrite(0));
        assertFalse(ctx.supportsItemComponentWrite(0));
        assertFalse(ctx.trySetItemExact(0, named(before, "after")));
        assertFalse(ctx.commitItemComponents(0, before, named(before, "after")));
        assertFalse(ctx.commitItemComponents(0, before, before.copy()));

        assertStack(before, ctx.getItem(0));
        assertTrue(ctx.syncedSlots.isEmpty());
    }

    @Test
    void nonModifiableHandlerNeverUsesExtractInsertFallback() {
        ReadOnlyHandler handler = new ReadOnlyHandler(1, false);
        handler.slots[0] = original(7);
        RecordingContext ctx = new RecordingContext(handler);
        ItemStack before = ctx.getItem(0).copy();
        ItemStack after = named(before, "after");

        assertFalse(ctx.supportsItemComponentWrite(0));
        assertFalse(ctx.trySetItemExact(0, after));
        assertFalse(ctx.commitItemComponents(0, before, after));

        assertStack(before, handler.slots[0]);
        assertTrue(ctx.syncedSlots.isEmpty());
        assertNoFallback(handler);
    }

    @Test
    void successfulCommitNotifiesBothAssociatedBlockEntitiesOnce() {
        WritableHandler handler = handler(false);
        BlockEntity first = mock(BlockEntity.class);
        BlockEntity second = mock(BlockEntity.class);
        RecordingContext ctx = new RecordingContext(handler,
            List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), List.of(first, second));
        ItemStack before = ctx.getItem(0).copy();

        assertTrue(ctx.commitItemComponents(0, before, named(before, "after")));

        verify(first, times(1)).setChanged();
        verify(second, times(1)).setChanged();
        assertEquals(List.of(0), ctx.syncedSlots);
        assertEquals(1, handler.writes);
    }

    @Test
    void realSyncMarksDirtySlotAndBumpsRevisionExactlyOnce() {
        WritableHandler handler = handler(false);
        SimpleContainerContext ctx = new SimpleContainerContext(handler);
        TickContext tick = new TickContext(ctx);
        try (MockedStatic<ContainerTickHooks> hooks = mockStatic(ContainerTickHooks.class)) {
            ctx.setTickContext(tick);
            hooks.verify(() -> ContainerTickHooks.fireTickStart(ctx, tick));
        }
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);
        ItemStack before = ctx.getItem(0).copy();
        ItemStack after = named(before, "after");
        try {
            assertTrue(ctx.commitItemComponents(0, before, after));
            assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
            assertEquals(Set.of(0), tick.dirtySlots);
            assertEquals(1, handler.writes);
            assertTrue(ctx.commitItemComponents(0, after, after.copy()));
            assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
            assertFalse(ctx.commitItemComponents(0, before, after));
            assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
        } finally {
            ctx.setTickContext(null);
            ContainerLivingItemHandler.removeDataByKey(ctx.getCacheKey());
        }
    }

    @ParameterizedTest(name = "马族逻辑槽 {0}: 跳过鞍槽并保留最后存储格")
    @ValueSource(ints = {0, 14})
    void entityCommitMapsLogicalStorageWithoutTouchingSaddle(int logicalSlot) {
        WritableHandler handler = new WritableHandler(16, false);
        for (int i = 0; i < handler.slots.length; i++) handler.slots[i] = original(i + 1);
        handler.slots[0] = new ItemStack(Items.SADDLE);
        ItemStack[] snapshots = Arrays.stream(handler.slots).map(ItemStack::copy).toArray(ItemStack[]::new);
        AbstractChestedHorse horse = mock(AbstractChestedHorse.class);
        when(horse.hasChest()).thenReturn(true);
        when(horse.getUUID()).thenReturn(UUID.randomUUID());
        when(horse.blockPosition()).thenReturn(BlockPos.ZERO);
        when(horse.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(handler);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        List<Integer> syncedSlots = new ArrayList<>();
        EntityContainerContext ctx = new EntityContainerContext(horse, level) {
            @Override public void syncSlotToClients(int slot, ItemStack stack) { syncedSlots.add(slot); }
        };
        ItemStack before = ctx.getItem(logicalSlot).copy();
        ItemStack after = named(before, "after");

        assertEquals(15, ctx.getSize());
        assertFalse(ctx.supportsItemComponentWrite(-1));
        assertFalse(ctx.supportsItemComponentWrite(15));
        assertFalse(ctx.commitItemComponents(-1, before, after));
        assertFalse(ctx.commitItemComponents(15, before, after));
        assertTrue(ctx.supportsItemComponentWrite(logicalSlot));
        assertTrue(ctx.commitItemComponents(logicalSlot, before, after));

        assertEquals(logicalSlot + 1, handler.lastWriteSlot);
        assertEquals(1, handler.writes);
        for (int i = 0; i < handler.slots.length; i++) {
            assertStack(i == logicalSlot + 1 ? after : snapshots[i], handler.slots[i]);
        }
        assertEquals(List.of(logicalSlot), syncedSlots);
        assertNoFallback(handler);
    }
}
