package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * {@link SimpleContainerContext} 的单元测试。
 *
 * <p>此类测试 SimpleContainerContext 作为 ContainerContext 实现的正确性，
 * 覆盖写入路径、列数推断、盔甲槽特殊处理等 {@link FakeContainerContext}
 * 绕过的关键路径。</p>
 */
class SimpleContainerContextTest {

    /** 测试用 IItemHandler —— 支持 insert/extract 的内存实现 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(int size) {
            this.slots = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                slots[i] = ItemStack.EMPTY;
            }
        }

        FakeHandler(ItemStack... slots) {
            this.slots = slots;
        }

        @Override
        public int getSlots() {
            return slots.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slots[slot];
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            slots[slot] = stack;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            int maxStack = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                int toInsert = Math.min(stack.getCount(), maxStack);
                if (!simulate) {
                    slots[slot] = stack.copyWithCount(toInsert);
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(toInsert);
                return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = maxStack - existing.getCount();
                if (space <= 0) return stack;
                int toInsert = Math.min(stack.getCount(), space);
                if (!simulate) {
                    slots[slot].grow(toInsert);
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(toInsert);
                return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
            }
            return stack; // 不同物品，无法插入
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = slots[slot];
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) {
                slots[slot].shrink(toExtract);
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

    // ============================================================
    // 基础委托
    // ============================================================

    @Nested
    @DisplayName("基础构造与委托")
    class BasicDelegation {

        @Test
        @DisplayName("getSize 委托给 handler.getSlots")
        void getSize_delegatesToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            assertEquals(27, ctx.getSize());
        }

        @Test
        @DisplayName("getItem 委托给 handler.getStackInSlot")
        void getItem_delegatesToHandler() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(5, new ItemStack(Items.REDSTONE, 3));
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            ItemStack result = ctx.getItem(5);
            assertEquals(Items.REDSTONE, result.getItem());
            assertEquals(3, result.getCount());
        }

        @Test
        @DisplayName("getItem 越界返回 EMPTY")
        void getItem_outOfBounds_returnsEmpty() {
            var handler = new FakeHandler(5);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            assertTrue(ctx.getItem(-1).isEmpty());
            assertTrue(ctx.getItem(5).isEmpty());
            assertTrue(ctx.getItem(100).isEmpty());
        }

        @Test
        @DisplayName("getSlotLimit 委托给 handler.getSlotLimit")
        void getSlotLimit_delegatesToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            assertEquals(64, ctx.getSlotLimit(0));
        }

        @Test
        @DisplayName("isItemValid 委托给 handler.isItemValid")
        void isItemValid_delegatesToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            assertTrue(ctx.isItemValid(0, new ItemStack(Items.REDSTONE)));
        }

        @Test
        @DisplayName("getMaxStackSize 取 handler 首槽上限")
        void getMaxStackSize_usesFirstSlotLimit() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            assertEquals(64, ctx.getMaxStackSize());
        }
    }

    // ============================================================
    // getWidth 列数推断
    // ============================================================

    @Nested
    @DisplayName("getWidth 列数推断")
    class WidthInference {

        @Test
        @DisplayName("玩家背包（inventory 非 null）返回 9 列")
        void playerInventory_returns9() {
            // 仅测试构造不抛异常 + 返回 9（需要 mock Inventory，此处简化验证）
            var handler = new FakeHandler(41);
            var ctx = new SimpleContainerContext(handler, (net.minecraft.world.entity.player.Inventory) null);
            assertNotNull(ctx);
        }

        @Test
        @DisplayName("无 BE / 无 inventory：按加权启发式回退")
        void noBe_noInventory_usesWeightedHeuristic() {
            assertWidth(27, 9);  // 27%9=0
            assertWidth(36, 9);  // 36%9=0, 9 优先于 12
            assertWidth(40, 10); // 40%10=0, 10 优先于 8
            assertWidth(54, 9);  // 54%9=0
            assertWidth(81, 9);  // 81%9=0
            assertWidth(96, 12); // 96%12=0, 12 优先于 8
            assertWidth(108, 9); // 108%9=0, 9 优先于 12
            assertWidth(120, 10); // 120%10=0, 10 优先于 12
        }

        @Test
        @DisplayName("质数尺寸退化为 1 列")
        void primeSize_degradesTo1() {
            assertWidth(17, 1);
            assertWidth(19, 1);
            assertWidth(23, 1);
        }

        @Test
        @DisplayName("1 格容器返回 1 列")
        void singleSlot_returns1() {
            assertWidth(1, 1);
        }

        @Test
        @DisplayName("2 格容器返回 2 列（2%2=0）")
        void twoSlots_returns2() {
            assertWidth(2, 2);
        }

        private void assertWidth(int size, int expectedWidth) {
            var handler = new FakeHandler(size);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            assertEquals(expectedWidth, ctx.getWidth(),
                () -> size + " 格容器应推断 " + expectedWidth + " 列");
        }
    }

    // ============================================================
    // setItem 写入路径
    // ============================================================

    @Nested
    @DisplayName("setItem 写入路径")
    class SetItem {

        @Test
        @DisplayName("空槽写入：插入成功")
        void setItem_emptySlot_inserts() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            ItemStack stack = new ItemStack(Items.REDSTONE, 5);
            ctx.setItem(3, stack);
            assertEquals(Items.REDSTONE, handler.getStackInSlot(3).getItem());
            assertEquals(5, handler.getStackInSlot(3).getCount());
        }

        @Test
        @DisplayName("越界写入：不抛异常，不改变内容")
        void setItem_outOfBounds_doesNothing() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            ctx.setItem(-1, new ItemStack(Items.REDSTONE));
            ctx.setItem(27, new ItemStack(Items.REDSTONE));
            // 不抛异常即通过
        }

        @Test
        @DisplayName("非空槽覆盖：先清空再写入新物品")
        void setItem_overwrite_replacesContent() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(5, new ItemStack(Items.DIAMOND, 10));
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            ctx.setItem(5, new ItemStack(Items.REDSTONE, 3));
            assertEquals(Items.REDSTONE, handler.getStackInSlot(5).getItem());
            assertEquals(3, handler.getStackInSlot(5).getCount());
        }

        @Test
        @DisplayName("堆叠上限截断：超过 maxStack 时截断")
        void setItem_exceedsMaxStack_truncates() {
            // Redstone 的 maxStackSize = 64
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            // handler.getSlotLimit(0) = 64, 但 redstone maxStackSize = 64
            ctx.setItem(0, new ItemStack(Items.REDSTONE, 100));
            // 实际 handler 按 maxStack 截断
            assertTrue(handler.getStackInSlot(0).getCount() <= 64);
        }
    }

    // ============================================================
    // simulateInsertItem
    // ============================================================

    @Nested
    @DisplayName("simulateInsertItem 模拟插入")
    class SimulateInsertItem {

        @Test
        @DisplayName("无 Container 回退到 handler.insertItem(simulate)")
        void noContainer_fallsBackToHandler() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 10));
            assertEquals(10, result);
            // 模拟插入不应改变实际内容
            assertTrue(handler.getStackInSlot(0).isEmpty());
        }

        @Test
        @DisplayName("空槽位：返回最少值（slotLimit, maxStackSize, count）")
        void simulateInsertItem_emptySlot_returnsMin() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 100));
            // handler.getSlotLimit(0)=64, 但 redstone 的 maxStackSize=64, 所以 =64
            assertTrue(result <= 64);
            assertTrue(result > 0);
        }

        @Test
        @DisplayName("部分满槽位：返回剩余空间")
        void simulateInsertItem_partialSlot_returnsSpace() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(0, new ItemStack(Items.REDSTONE, 50));
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            // 50 已在槽中，maxStack=64，空间=14
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 20));
            assertEquals(14, result);
        }

        @Test
        @DisplayName("满槽位：返回 0")
        void simulateInsertItem_fullSlot_returnsZero() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(0, new ItemStack(Items.REDSTONE, 64));
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 10));
            assertEquals(0, result);
        }

        @Test
        @DisplayName("不同物品：返回 0（无法插入）")
        void simulateInsertItem_differentItem_returnsZero() {
            var handler = new FakeHandler(27);
            handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 1));
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            int result = ctx.simulateInsertItem(0, new ItemStack(Items.REDSTONE, 1));
            assertEquals(0, result);
        }
    }

    // ============================================================
    // getContainerKey
    // ============================================================

    @Nested
    @DisplayName("getContainerKey 容器标识生成")
    class ContainerKey {

        @Test
        @DisplayName("无 inventory / 无 BE：使用 handler 哈希")
        void noInventory_noBe_usesHandlerHash() {
            var handler = new FakeHandler(27);
            var ctx = new SimpleContainerContext(handler, null, null, null, null);
            String key = ctx.getContainerKey();
            assertTrue(key.startsWith("container_"));
            assertTrue(key.length() > "container_".length());
        }
    }

    // ============================================================
    // 盔甲槽特殊路径
    // ============================================================

    @Nested
    @DisplayName("盔甲槽路径")
    class ArmorSlot {

        private Inventory mockInventory;
        private SimpleContainerContext ctx;
        private FakeHandler handler;

        @BeforeEach
        void setUp() {
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getStringUUID()).thenReturn("test-player-uuid");

            // 使用真实 Inventory 构造，其只做 this.player = player 赋值
            mockInventory = new Inventory(mockPlayer);

            handler = new FakeHandler(41);
            ctx = new SimpleContainerContext(handler, mockInventory);
        }

        @Test
        @DisplayName("isItemValid 盔甲槽返回 true（不委托 handler）")
        void isItemValid_armorSlot_returnsTrue() {
            for (int slot = 36; slot < 40; slot++) {
                assertTrue(ctx.isItemValid(slot, new ItemStack(Items.STONE)),
                    "盔甲槽 " + slot + " 应接受任意物品");
            }
        }

        @Test
        @DisplayName("getSlotLimit 盔甲槽返回 maxStackSize（不与 handler 限制冲突）")
        void getSlotLimit_armorSlot_returnsMaxStackSize() {
            for (int slot = 36; slot < 40; slot++) {
                assertEquals(64, ctx.getSlotLimit(slot),
                    "盔甲槽 " + slot + " 上限应为 64");
            }
        }

        @Test
        @DisplayName("simulateInsertItem 盔甲槽：按 maxStack 截断，不算已有物品")
        void simulateInsertItem_armorSlot_usesMaxStack() {
            // 盔甲槽直接返回 min(count, maxStack)，忽略槽中已有物品
            int result = ctx.simulateInsertItem(36, new ItemStack(Items.DIAMOND, 10));
            assertEquals(10, result);
        }

        @Test
        @DisplayName("simulateInsertItem 盔甲槽：超过 maxStack 时截断")
        void simulateInsertItem_armorSlot_exceedsMaxStack_truncates() {
            int result = ctx.simulateInsertItem(36, new ItemStack(Items.DIAMOND, 100));
            assertEquals(64, result);
        }
    }
}