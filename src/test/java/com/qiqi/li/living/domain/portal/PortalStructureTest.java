package com.qiqi.li.living.domain.portal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 四邻围闭、固定区域及门面内容准入守卫。 */
class PortalStructureTest {
    @AfterEach
    void cleanCaches() {
        ContainerLivingItemHandler.clearAllCaches();
    }

    private static ItemStack frame() {
        ItemStack stack = new ItemStack(Items.OBSIDIAN);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static FakeContainerContext cross() {
        FakeContainerContext ctx = new FakeContainerContext(9, 3);
        for (int slot : Set.of(1, 3, 5, 7)) ctx.rawSet(slot, frame());
        return ctx;
    }

    private static FakeContainerContext box(int width, int height) {
        FakeContainerContext ctx = new FakeContainerContext(width * height, width);
        for (int slot = 0; slot < ctx.getSize(); slot++) {
            int row = slot / width;
            int col = slot % width;
            if (row == 0 || row == height - 1 || col == 0 || col == width - 1) ctx.rawSet(slot, frame());
        }
        return ctx;
    }

    @Test
    void fourFramesWithoutCornersAreEnough() {
        FakeContainerContext ctx = cross();
        assertEquals(Set.of(4), PortalStructure.findEnclosed(ctx, 4));
        assertTrue(PortalStructure.canIgnite(ctx, Set.of(4), slot -> false));
    }

    @Test
    void everyNecessaryOpeningLeaks() {
        for (int opening : Set.of(1, 3, 5, 7)) {
            FakeContainerContext ctx = cross();
            ctx.rawSet(opening, ItemStack.EMPTY);
            assertTrue(PortalStructure.findEnclosed(ctx, 4).isEmpty());
            assertFalse(PortalStructure.validate(ctx, Set.of(4), slot -> false));
        }
    }

    @Test
    void ordinaryObsidianIsNotAFrame() {
        FakeContainerContext ctx = cross();
        ctx.rawSet(1, new ItemStack(Items.OBSIDIAN));
        assertTrue(PortalStructure.findEnclosed(ctx, 4).isEmpty());
        assertFalse(PortalStructure.isFrame(new ItemStack(Items.CRYING_OBSIDIAN)));
        assertFalse(PortalStructure.isFrame(ItemStack.EMPTY));
    }

    @Test
    void gridEdgeIsNotFreeWallButFullFrameCanTouchEdge() {
        FakeContainerContext ctx = new FakeContainerContext(9, 3);
        for (int slot : Set.of(0, 2, 4)) ctx.rawSet(slot, frame());
        assertTrue(PortalStructure.findEnclosed(ctx, 1).isEmpty());
        assertFalse(PortalStructure.validate(ctx, Set.of(1), slot -> false));
        assertTrue(PortalStructure.canIgnite(box(3, 3), Set.of(4), slot -> false));
    }

    @Test
    void incompleteLastRowIsAnOpeningAndRowsDoNotWrap() {
        FakeContainerContext ctx = new FakeContainerContext(8, 3);
        for (int slot : Set.of(1, 3, 5, 7)) ctx.rawSet(slot, frame());
        assertEquals(Set.of(4), PortalStructure.findEnclosed(ctx, 4));
        assertEquals(-1, ContainerContext.resolveNeighbor(7, ContainerContext.E_RIGHT, 8, 3));
        assertTrue(PortalStructure.findEnclosed(ctx, 6).isEmpty());
        assertEquals(-1, ContainerContext.resolveNeighbor(2, ContainerContext.E_RIGHT, 9, 3));
    }

    @Test
    void arbitraryConcaveRegionIsSupported() {
        FakeContainerContext ctx = new FakeContainerContext(36, 6);
        Set<Integer> region = Set.of(7, 8, 9, 13, 19, 20);
        for (int slot : region) {
            for (int direction = 0; direction < 4; direction++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, direction, 36, 6);
                if (!region.contains(neighbor)) ctx.rawSet(neighbor, frame());
            }
        }
        assertEquals(region, PortalStructure.findEnclosed(ctx, 7));
        assertTrue(PortalStructure.validate(ctx, region, slot -> false));
    }

    @Test
    void sharedBoundaryAllowsTwoIndependentDoors() {
        FakeContainerContext ctx = box(5, 3);
        ctx.rawSet(7, frame());
        assertEquals(Set.of(6), PortalStructure.findEnclosed(ctx, 6));
        assertEquals(Set.of(8), PortalStructure.findEnclosed(ctx, 8));
        assertTrue(PortalStructure.validate(ctx, Set.of(6), slot -> false));
        assertTrue(PortalStructure.validate(ctx, Set.of(8), slot -> false));
        ctx.rawSet(7, ItemStack.EMPTY);
        assertFalse(PortalStructure.validate(ctx, Set.of(6), slot -> false));
        assertFalse(PortalStructure.validate(ctx, Set.of(8), slot -> false));
        assertEquals(Set.of(6, 7, 8), PortalStructure.findEnclosed(ctx, 6));
    }

    @Test
    void fluidInAnyInteriorSlotRejectsWholeRegion() {
        FakeContainerContext ctx = box(5, 3);
        Set<Integer> region = Set.of(6, 7, 8);
        for (int blocked : region) {
            assertFalse(PortalStructure.canIgnite(ctx, region, slot -> slot == blocked));
            assertFalse(PortalStructure.validate(ctx, region, slot -> slot == blocked));
        }
        assertTrue(PortalStructure.validate(ctx, region, slot -> slot == 0));
    }

    @Test
    void ordinaryCargoPreventsIgnitionButDoesNotExtinguishExistingDoor() {
        FakeContainerContext ctx = box(5, 3);
        Set<Integer> region = Set.of(6, 7, 8);
        ctx.rawSet(8, new ItemStack(Items.DIAMOND, 64));
        assertEquals(region, PortalStructure.findEnclosed(ctx, 6));
        assertFalse(PortalStructure.canIgnite(ctx, region, slot -> false));
        assertTrue(PortalStructure.validate(ctx, region, slot -> false));
    }

    @Test
    void anyLivingItemInsideExtinguishesEntireRegionIncludingInsertedFrame() {
        FakeContainerContext ctx = box(5, 3);
        Set<Integer> region = Set.of(6, 7, 8);
        ItemStack living = new ItemStack(Items.STONE);
        LivingItemManager.setLiving(living, true);
        ctx.rawSet(7, living);
        assertFalse(PortalStructure.validate(ctx, region, slot -> false));
        ctx.rawSet(7, frame());
        assertFalse(PortalStructure.validate(ctx, region, slot -> false));
    }

    @Test
    void replacementFrameAndStackCountDoNotChangeBoundaryOwnership() {
        FakeContainerContext ctx = cross();
        ItemStack replacement = frame();
        replacement.setCount(64);
        ctx.rawSet(1, replacement);
        assertTrue(PortalStructure.validate(ctx, Set.of(4), slot -> false));
    }

    @Test
    void invalidSlotsEmptyDisconnectedAndChangedWidthFailSafely() {
        FakeContainerContext ctx = cross();
        assertTrue(PortalStructure.findEnclosed(ctx, -1).isEmpty());
        assertTrue(PortalStructure.findEnclosed(ctx, 9).isEmpty());
        assertTrue(PortalStructure.findEnclosed(ctx, 1).isEmpty());
        assertFalse(PortalStructure.validate(ctx, Set.of(), slot -> false));
        assertFalse(PortalStructure.validate(ctx, Set.of(-1), slot -> false));
        assertFalse(PortalStructure.validate(ctx, Set.of(9), slot -> false));
        assertFalse(PortalStructure.validate(ctx, new ContainerPortalData.PortalRegion(4, Set.of(4)), slot -> false));
        FakeContainerContext two = box(5, 3);
        two.rawSet(7, frame());
        assertFalse(PortalStructure.validate(two, Set.of(6, 8), slot -> false));
        assertTrue(PortalStructure.findEnclosed(new FakeContainerContext(0, 0), 0).isEmpty());
    }

    @Test
    void largeRealGridHasNoFixedDoorSizeLimit() {
        FakeContainerContext ctx = box(100, 100);
        Set<Integer> expected = new HashSet<>();
        for (int row = 1; row < 99; row++) {
            for (int col = 1; col < 99; col++) expected.add(row * 100 + col);
        }
        Set<Integer> found = PortalStructure.findEnclosed(ctx, 101);
        assertEquals(expected, found);
        assertTrue(PortalStructure.validate(ctx, found, slot -> false));
        assertThrows(UnsupportedOperationException.class, () -> found.add(0));
    }
}
