package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.qiqi.li.living.transfer.internal.ItemFilterComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 共享过滤判定与槽位访问器回归。 */
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

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "3, 3", "4, 1", "64, 1", "1024, 1", "2147483647, 1"})
    @DisplayName("堆叠模式：仅2为NBT、仅3为Tag，其余为ID")
    void filterMode_usesExactStackCounts(int count, int expectedMode) {
        assertEquals(expectedMode, ItemFilterComponent.normalizeMode(count), "count=" + count);
    }

    @Test
    @DisplayName("同级ID黑白名单冲突：黑名单优先，实际栈与类型筛选一致")
    void equalIdPriority_blacklistWins() {
        FilterData filter = new FilterData(List.of("minecraft:diamond"), List.of("minecraft:diamond"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        FakeAccessor delegate = new FakeAccessor();
        delegate.slot = new ItemStack(Items.DIAMOND);

        assertTrue(new FilteredSlotAccessor(delegate, filter).simulateExtract(1).isEmpty());
        assertFalse(ItemFilterComponent.allowsItemType(filter, "minecraft:diamond"));
        assertFalse(ItemFilterComponent.allows(filter, new ItemStack(Items.IRON_INGOT)),
            "存在白名单时，不匹配的物品不能放行");
        assertEquals(1, delegate.slot.getCount());
    }

    @Test
    @DisplayName("NBT白名单高于ID黑名单；类型筛选不冒充实际栈匹配")
    void compositeWhitelist_overridesIdBlacklistOnlyForMatchingStack() {
        ItemStack named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("filtered"));
        FilterData filter = new FilterData(List.of("minecraft:diamond"), List.of(), List.of(), List.of(),
            List.of(), List.of(ItemFilterComponent.getCompositeKey(named)),
            List.of(), List.of(), List.of(), List.of());
        FakeAccessor delegate = new FakeAccessor();
        delegate.slot = named.copy();

        ItemStack result = new FilteredSlotAccessor(delegate, filter).simulateExtract(1);
        assertTrue(ItemStack.isSameItemSameComponents(named, result));
        assertFalse(ItemFilterComponent.allows(filter, new ItemStack(Items.DIAMOND)));
        assertFalse(ItemFilterComponent.allowsItemType(filter, "minecraft:diamond"));
        assertEquals(1, delegate.slot.getCount());
    }

    @Test
    @DisplayName("同侧NBT条目遮蔽ID条目；只有指定组件变体放行")
    void compositeWhitelist_shadowsBroadIdWhitelist() {
        ItemStack named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("filtered"));
        FilterData filter = new FilterData(List.of(), List.of("minecraft:diamond"), List.of(), List.of(),
            List.of(), List.of(ItemFilterComponent.getCompositeKey(named)),
            List.of(), List.of(), List.of(), List.of());

        assertTrue(ItemFilterComponent.allows(filter, named));
        assertFalse(ItemFilterComponent.allows(filter, new ItemStack(Items.DIAMOND)));
        assertFalse(ItemFilterComponent.allowsItemType(filter, "minecraft:diamond"));
    }

    @Test
    @DisplayName("同级NBT黑白名单冲突：黑名单优先")
    void equalCompositePriority_blacklistWins() {
        ItemStack item = new ItemStack(Items.DIAMOND);
        String composite = ItemFilterComponent.getCompositeKey(item);
        FilterData filter = new FilterData(List.of(), List.of(), List.of(), List.of(),
            List.of(composite), List.of(composite), List.of(), List.of(), List.of(), List.of());

        assertFalse(ItemFilterComponent.allows(filter, item));
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
