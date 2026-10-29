package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * {@link NeighborSlotAccessor} 的接缝测试。
 *
 * <p>它是跨容器传输的邻居侧入口：抽取/插入必须遵守 {@code IItemHandler} 契约，
 * 插入要正确回写调用方栈的余量（否则源会被多扣），堆叠插入要按“先合并再找空槽”工作。</p>
 */
class NeighborSlotAccessorTest {

    private static NeighborSlotAccessor accessor(ItemStackHandler handler, int slot) {
        return new NeighborSlotAccessor(handler, slot, null, BlockPos.ZERO);
    }

    @Test
    @DisplayName("抽取:从指定槽扣减,返回精确数量")
    void extract_removesFromSlot() {
        ItemStackHandler handler = new ItemStackHandler(2);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        NeighborSlotAccessor accessor = accessor(handler, 0);

        ItemStack out = accessor.extract(2, null);

        assertEquals(2, out.getCount());
        assertEquals(1, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("模拟抽取:不扣减")
    void simulateExtract_doesNotRemove() {
        ItemStackHandler handler = new ItemStackHandler(2);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        NeighborSlotAccessor accessor = accessor(handler, 0);

        assertEquals(2, accessor.simulateExtract(2).getCount());
        assertEquals(3, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("插入:下层收下,来栈按余量回写(全额 ⇒ 空)")
    void insert_mutatesInputToRemainder() {
        ItemStackHandler handler = new ItemStackHandler(2);
        NeighborSlotAccessor accessor = accessor(handler, 0);
        ItemStack input = new ItemStack(Items.DIAMOND, 3);

        int inserted = accessor.insert(input);

        assertEquals(3, inserted);
        assertTrue(input.isEmpty(), "全额收下 ⇒ 来栈清空");
        assertEquals(3, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("模拟插入:可接受数量正确且不写内容")
    void simulateInsert_doesNotWrite() {
        ItemStackHandler handler = new ItemStackHandler(2);
        NeighborSlotAccessor accessor = accessor(handler, 0);
        ItemStack input = new ItemStack(Items.DIAMOND, 3);

        assertEquals(3, accessor.simulateInsert(input));
        assertEquals(3, input.getCount(), "模拟不得修改来栈");
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    @DisplayName("插入部分装下:返回实际插入数,来栈保留余量")
    void insert_partial_leavesRemainder() {
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 60));
        NeighborSlotAccessor accessor = accessor(handler, 0);
        ItemStack input = new ItemStack(Items.DIAMOND, 10);

        int inserted = accessor.insert(input);

        assertEquals(4, inserted);
        assertEquals(6, input.getCount(), "余量必须回写到调用方栈");
        assertEquals(64, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("回滚同物品:回到原槽")
    void rollback_sameItem_returnsToSlot() {
        ItemStackHandler handler = new ItemStackHandler(2);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 1));
        NeighborSlotAccessor accessor = accessor(handler, 0);

        accessor.rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(3, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("回滚异物品:原槽被占 ⇒ 只报告,不写其它槽")
    void rollback_differentItem_reportsAndDoesNotWriteElsewhere() {
        ItemStackHandler handler = new ItemStackHandler(2);
        handler.setStackInSlot(0, new ItemStack(Items.STONE, 1));
        NeighborSlotAccessor accessor = accessor(handler, 0);

        accessor.rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(1, handler.getStackInSlot(0).getCount(), "原槽不动");
        assertTrue(handler.getStackInSlot(1).isEmpty(), "不得写入其它槽(只报告日志)");
    }

    @Test
    @DisplayName("回滚满槽:只报告,不溢出到其它槽")
    void rollback_fullSlot_reportsAndDoesNotOverflow() {
        ItemStackHandler handler = new ItemStackHandler(2);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64));
        NeighborSlotAccessor accessor = accessor(handler, 0);

        accessor.rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(64, handler.getStackInSlot(0).getCount());
        assertTrue(handler.getStackInSlot(1).isEmpty(), "不得写入其它槽(只报告日志)");
    }

    @Test
    @DisplayName("isFull / getter:槽满判据与访问器身份")
    void fullAndGetters() {
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64));
        NeighborSlotAccessor accessor = accessor(handler, 0);

        assertTrue(accessor.isFull());
        assertSame(handler, accessor.getHandler());
        assertEquals(0, accessor.getSlot());

        handler.setStackInSlot(0, ItemStack.EMPTY);
        assertFalse(accessor.isFull());
        assertTrue(accessor.isEmpty());
    }
}
