package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link FilteredSlotAccessor} 的接缝测试。
 *
 * <p>它是活漏斗黑白名单的唯一执行点：过滤必须发生在**下层调用之前**（插入）
 * 或**发现违规后立即回滚**（抽取），否则会从错误来源抽走物品。</p>
 */
class FilteredSlotAccessorTest {

    /** 最小替身：记录调用次数，并按 SlotAccessor 契约修改传入栈。 */
    private static final class FakeAccessor implements SlotAccessor {
        ItemStack slot = ItemStack.EMPTY;
        int insertCalls;
        int simulateInsertCalls;
        int rollbackCalls;

        @Override
        public ItemStack extract(int amount, ItemStack filterType) {
            if (slot.isEmpty()) return ItemStack.EMPTY;
            int n = Math.min(amount, slot.getCount());
            ItemStack out = slot.copyWithCount(n);
            slot.shrink(n);
            if (slot.isEmpty()) slot = ItemStack.EMPTY;
            return out;
        }

        @Override
        public ItemStack simulateExtract(int amount) {
            if (slot.isEmpty()) return ItemStack.EMPTY;
            return slot.copyWithCount(Math.min(amount, slot.getCount()));
        }

        @Override
        public int insert(ItemStack stack) {
            insertCalls++;
            if (slot.isEmpty()) {
                int n = stack.getCount();
                slot = stack.copy();
                stack.setCount(0);
                return n;
            }
            if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int n = Math.min(64 - slot.getCount(), stack.getCount());
                slot.grow(n);
                stack.shrink(n);
                return n;
            }
            return 0;
        }

        @Override
        public int simulateInsert(ItemStack stack) {
            simulateInsertCalls++;
            if (slot.isEmpty()) return stack.getCount();
            return ItemStack.isSameItemSameComponents(slot, stack) ? 64 - slot.getCount() : 0;
        }

        @Override
        public void rollback(ItemStack stack) {
            rollbackCalls++;
            if (slot.isEmpty()) {
                slot = stack.copy();
            } else {
                slot.grow(stack.getCount());
            }
            stack.setCount(0);
        }

        @Override
        public boolean isEmpty() {
            return slot.isEmpty();
        }

        @Override
        public boolean isFull() {
            return slot.getCount() >= 64;
        }

        @Override
        public void markTransferred() {}

        @Override
        public void sync() {}
    }

    private static FilterData blacklist(String id) {
        return new FilterData(List.of(id), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("抽取被黑名单拒绝:立即回滚,物品回到下层")
    void extract_blacklisted_rollsBack() {
        FakeAccessor delegate = new FakeAccessor();
        delegate.slot = new ItemStack(Items.DIAMOND);
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate,
            blacklist("minecraft:diamond"));

        ItemStack out = filtered.extract(1, null);

        assertTrue(out.isEmpty(), "黑名单物品不得输出");
        assertEquals(1, delegate.rollbackCalls, "抽取后才发现违规 ⇒ 必须回滚");
        assertEquals(1, delegate.slot.getCount(), "回滚后物品回到下层");
    }

    @Test
    @DisplayName("抽取放行:不做回滚,物品正常输出")
    void extract_allowed_passesThrough() {
        FakeAccessor delegate = new FakeAccessor();
        delegate.slot = new ItemStack(Items.IRON_INGOT);
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate,
            blacklist("minecraft:diamond"));

        ItemStack out = filtered.extract(1, null);

        assertEquals(Items.IRON_INGOT, out.getItem());
        assertEquals(0, delegate.rollbackCalls);
        assertTrue(delegate.slot.isEmpty());
    }

    @Test
    @DisplayName("模拟抽取被拒:不触碰下层(不回滚,因为还没抽)")
    void simulateExtract_blacklisted_returnsEmptyWithoutRollback() {
        FakeAccessor delegate = new FakeAccessor();
        delegate.slot = new ItemStack(Items.DIAMOND);
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate,
            blacklist("minecraft:diamond"));

        assertTrue(filtered.simulateExtract(1).isEmpty());
        assertEquals(0, delegate.rollbackCalls, "模拟不得回滚");
        assertEquals(1, delegate.slot.getCount(), "模拟不得改内容");
    }

    @Test
    @DisplayName("插入被黑名单拒绝:不调用下层,来栈保持不变")
    void insert_blacklisted_isRejectedBeforeDelegate() {
        FakeAccessor delegate = new FakeAccessor();
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate,
            blacklist("minecraft:diamond"));
        ItemStack input = new ItemStack(Items.DIAMOND);

        int inserted = filtered.insert(input);

        assertEquals(0, inserted);
        assertEquals(0, delegate.insertCalls, "拒绝必须在到达下层之前");
        assertEquals(1, input.getCount(), "拒绝不得修改来栈");
    }

    @Test
    @DisplayName("插入放行:委托下层并返回实际插入数")
    void insert_allowed_delegates() {
        FakeAccessor delegate = new FakeAccessor();
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate,
            blacklist("minecraft:diamond"));
        ItemStack input = new ItemStack(Items.IRON_INGOT);

        int inserted = filtered.insert(input);

        assertEquals(1, inserted);
        assertEquals(1, delegate.insertCalls);
        assertEquals(Items.IRON_INGOT, delegate.slot.getItem());
    }

    @Test
    @DisplayName("模拟插入被拒:不调用下层")
    void simulateInsert_blacklisted_doesNotReachDelegate() {
        FakeAccessor delegate = new FakeAccessor();
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate,
            blacklist("minecraft:diamond"));

        assertEquals(0, filtered.simulateInsert(new ItemStack(Items.DIAMOND)));
        assertEquals(0, delegate.simulateInsertCalls);
    }

    @Test
    @DisplayName("rollback / unwrap:原样委托")
    void rollbackAndUnwrap_delegate() {
        FakeAccessor delegate = new FakeAccessor();
        FilteredSlotAccessor filtered = new FilteredSlotAccessor(delegate, FilterData.EMPTY);

        filtered.rollback(new ItemStack(Items.DIAMOND));

        assertEquals(1, delegate.rollbackCalls);
        assertSame(delegate, filtered.unwrap());
    }

    @Test
    @DisplayName("null / EMPTY 过滤器:全部放行")
    void nullOrEmptyFilter_allowsEverything() {
        FakeAccessor nullDelegate = new FakeAccessor();
        FilteredSlotAccessor nullFilter = new FilteredSlotAccessor(nullDelegate, null);
        assertEquals(1, nullFilter.insert(new ItemStack(Items.DIAMOND)), "null 过滤器放行");

        FakeAccessor emptyDelegate = new FakeAccessor();
        FilteredSlotAccessor emptyFilter = new FilteredSlotAccessor(emptyDelegate, FilterData.EMPTY);
        assertEquals(1, emptyFilter.insert(new ItemStack(Items.DIAMOND)), "EMPTY 过滤器放行");
    }
}
