package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;

/** 槽到源世界的真实交接与受限恢复；不声称覆盖双世界实体生命周期。 */
class PortalItemTransferTest {

    private static final Vec3 POS = new Vec3(3.25, 64.5, -8.75);

    private static ServerLevel level() throws ReflectiveOperationException {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        Field random = Level.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(level, RandomSource.create(42));
        return level;
    }

    private static PortalPositionResolver.Origin origin(ServerLevel level) {
        return new PortalPositionResolver.Origin(level, POS);
    }

    private static ItemStack tagged(int amount) {
        ItemStack stack = new ItemStack(Items.DIAMOND, amount);
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "original cargo");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 64, 500, 4096})
    void realSourceEntityGetsOneBoundedIndependentPayload(int available) throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1);
        ItemStack before = tagged(available);
        ctx.set(0, before);
        AtomicReference<ItemEntity> spawned = new AtomicReference<>();
        when(level.addFreshEntity(any())).thenAnswer(invocation -> {
            spawned.set(invocation.getArgument(0));
            return true;
        });

        assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));

        int batch = Math.min(64, available);
        ItemEntity entity = spawned.get();
        assertNotNull(entity);
        assertSame(level, entity.level());
        assertEquals(POS, entity.position());
        assertEquals(Vec3.ZERO, entity.getDeltaMovement());
        assertTrue(entity.hasPickUpDelay(), "源出生有默认拾取延迟");
        assertTrue(ItemStack.matches(tagged(batch), entity.getItem()));
        assertNotSame(before, entity.getItem());
        assertNotSame(ctx.getItem(0), entity.getItem());
        assertEquals(available - batch, ctx.getItem(0).getCount());
        assertNotNull(entity.portalProcess);
        assertTrue(entity.portalProcess.isSamePortal((Portal) Blocks.NETHER_PORTAL));
        assertEquals(BlockPos.containing(POS), entity.portalProcess.getEntryPosition());
        assertTrue(entity.portalProcess.isInsidePortalThisTick());
        assertEquals(0, entity.tickCount, "不手动驱动实体 tick");
        verify(level, times(1)).addFreshEntity(entity);
        verify(level, never()).addDuringTeleport(any());
    }

    @Test
    void componentMaxStack99SendsOneNbtBoundedBatch() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1);
        ItemStack stack = tagged(500);
        stack.set(DataComponents.MAX_STACK_SIZE, 99);
        ctx.set(0, stack);
        AtomicReference<ItemEntity> entity = new AtomicReference<>();
        when(level.addFreshEntity(any())).thenAnswer(invocation -> {
            entity.set(invocation.getArgument(0));
            return true;
        });

        assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(99, entity.get().getItem().getCount());
        assertEquals(401, ctx.getItem(0).getCount());
        assertEquals(99, entity.get().getItem().getMaxStackSize());
    }

    @Test
    void smallerItemMaximumKeepsOversizedRemainderAtSource() throws Exception {
        ServerLevel level = level();
        AtomicReference<ItemEntity> entity = new AtomicReference<>();
        when(level.addFreshEntity(any())).thenAnswer(invocation -> {
            entity.set(invocation.getArgument(0));
            return true;
        });
        for (var item : java.util.List.of(Items.ENDER_PEARL, Items.DIAMOND_SWORD)) {
            FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, new ItemStack(item, 500));
            assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));
            int max = item.getDefaultMaxStackSize();
            assertEquals(max, entity.get().getItem().getCount());
            assertEquals(500 - max, ctx.getItem(0).getCount());
            assertSame(item, entity.get().getItem().getItem());
        }
    }

    @Test
    void reportedConsumeWithoutRealSourceDecreaseDoesNotSpawn() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = spy(new FakeContainerContext(1, 1).set(0, tagged(80)));
        doReturn(64).when(ctx).consume(0, 64);
        assertEquals(PortalItemTransfer.Result.SOURCE_CHANGED, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(80, ctx.getItem(0).getCount());
        verify(level, never()).addFreshEntity(any());
    }

    @Test
    void originalContainerItemTypeAndContentsAreNotUnwrapped() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1);
        ItemStack chest = new ItemStack(Items.CHEST, 2);
        chest.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(
            java.util.List.of(tagged(12))));
        ctx.set(0, chest);
        AtomicReference<ItemEntity> entity = new AtomicReference<>();
        when(level.addFreshEntity(any())).thenAnswer(invocation -> {
            entity.set(invocation.getArgument(0));
            return true;
        });

        assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertTrue(ItemStack.matches(chest, entity.get().getItem()), "交付箱子本体与原 CONTAINER 组件");
        assertTrue(ctx.getItem(0).isEmpty());
    }

    @Test
    void acceptedSpawnMarksExactlyOnceWithoutTickOrDirectDimensionOrRemovedInspection() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, tagged(8));
        when(level.addFreshEntity(any())).thenReturn(true);
        try (MockedConstruction<ItemEntity> entities = mockConstruction(ItemEntity.class)) {
            assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));
            assertEquals(1, entities.constructed().size());
            ItemEntity entity = entities.constructed().getFirst();
            verify(entity, times(1)).setAsInsidePortal((Portal) Blocks.NETHER_PORTAL, BlockPos.containing(POS));
            verify(entity).setDeltaMovement(Vec3.ZERO);
            verify(entity).setDefaultPickUpDelay();
            verify(entity, never()).tick();
            verify(entity, never()).changeDimension(any());
            verify(entity, never()).isRemoved();
        }
    }

    @Test
    void extractionPermissionAndExactWriterAreCheckedBeforeConsumeOrSpawn() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = spy(new FakeContainerContext(1, 1).set(0, tagged(8)));
        doReturn(false).when(ctx).supportsExactWrite(0);
        assertEquals(PortalItemTransfer.Result.EXACT_WRITE_UNSUPPORTED, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        doReturn(true).when(ctx).supportsExactWrite(0);
        doReturn(0).when(ctx).simulateExtractItem(eq(0), anyInt());
        assertEquals(PortalItemTransfer.Result.SOURCE_NOT_EXTRACTABLE, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(8, ctx.getItem(0).getCount());
        verify(ctx, never()).consume(anyInt(), anyInt());
        verify(level, never()).addFreshEntity(any());
    }

    @Test
    void partialExtractionBudgetOnlySpawnsConfirmedAmount() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            @Override public int simulateExtractItem(int slot, int amount) { return Math.min(5, amount); }
        };
        ctx.set(0, tagged(80));
        when(level.addFreshEntity(any())).thenReturn(true);
        assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(75, ctx.getItem(0).getCount());
        verify(level).addFreshEntity(argThat(entity -> ((ItemEntity) entity).getItem().getCount() == 5));
    }

    @Test
    void normalSpawnRejectionRestoresBeforeAndDoesNotMarkPortal() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, tagged(500));
        try (MockedConstruction<ItemEntity> entities = mockConstruction(ItemEntity.class)) {
            assertEquals(PortalItemTransfer.Result.SPAWN_REJECTED, PortalItemTransfer.transfer(ctx, 0, origin(level)));
            assertTrue(ItemStack.matches(tagged(500), ctx.getItem(0)));
            assertEquals(1, entities.constructed().size());
            verify(entities.constructed().getFirst(), never()).setAsInsidePortal(any(), any());
            verify(level, times(1)).addFreshEntity(any());
        }
    }

    @Test
    void rejectedSpawnCannotOverwriteChangedSource() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, tagged(80));
        when(level.addFreshEntity(any())).thenAnswer(invocation -> {
            ctx.set(0, new ItemStack(Items.GOLD_INGOT, 3));
            return false;
        });
        assertEquals(PortalItemTransfer.Result.SOURCE_CHANGED, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertTrue(ctx.getItem(0).is(Items.GOLD_INGOT));
        assertEquals(3, ctx.getItem(0).getCount());
    }

    @Test
    void rejectedSpawnReportsRollbackFailureWhenSetterMutatesRestorationArgument() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            int writes;
            @Override public void setItem(int slot, ItemStack stack) {
                if (++writes == 2) stack.setCount(1);
                super.setItem(slot, stack);
            }
        };
        ctx.set(0, tagged(80));
        assertEquals(PortalItemTransfer.Result.ROLLBACK_FAILED, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(1, ctx.getItem(0).getCount(), "失败不伪报已恢复80");
    }

    @Test
    void spawnExceptionDoesNotAssumeNoDeliveryOrRefill() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, tagged(80));
        when(level.addFreshEntity(any())).thenThrow(new IllegalStateException("spawn callback failed"));
        assertEquals(PortalItemTransfer.Result.DELIVERY_UNCERTAIN, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(16, ctx.getItem(0).getCount());
        verify(level, times(1)).addFreshEntity(any());
    }

    @Test
    void acceptedButRemovedEntityNeverRefillsSource() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, tagged(80));
        when(level.addFreshEntity(any())).thenAnswer(invocation -> {
            ((Entity) invocation.getArgument(0)).discard();
            return true;
        });
        assertEquals(PortalItemTransfer.Result.SUCCESS, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        assertEquals(16, ctx.getItem(0).getCount(), "源交接成功后 removed 不是回填依据");
    }

    @Test
    void invalidAddressesAndOtherDimensionsDoNotSpawn() throws Exception {
        ServerLevel level = level();
        FakeContainerContext ctx = new FakeContainerContext(1, 1).set(0, tagged(8));
        assertEquals(PortalItemTransfer.Result.INVALID_SLOT, PortalItemTransfer.transfer(ctx, -1, origin(level)));
        assertEquals(PortalItemTransfer.Result.INVALID_ORIGIN, PortalItemTransfer.transfer(ctx, 0,
            new PortalPositionResolver.Origin(level, new Vec3(Double.NaN, 64, 0))));
        when(level.dimension()).thenReturn(Level.END);
        assertEquals(PortalItemTransfer.Result.UNSUPPORTED_DIMENSION, PortalItemTransfer.transfer(ctx, 0, origin(level)));
        verify(level, never()).addFreshEntity(any());
        assertEquals(8, ctx.getItem(0).getCount());
    }
}
