package com.qiqi.li.living.transfer;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.components.ItemFilterComponent;

/**
 * 带过滤的槽位访问装饰器。
 *
 * <p>在底层 {@code SlotAccessor} 之上叠加 {@code FilterData} 过滤：插入 / 提取前先经
 * {@link ItemFilterComponent#allows} 判定，被拒则回滚或返回空，从而实现活漏斗的黑白名单过滤。</p>
 */
public class FilteredSlotAccessor implements SlotAccessor {

    private final SlotAccessor delegate;
    private final FilterData filterData;

    public FilteredSlotAccessor(SlotAccessor delegate, FilterData filterData) {
        this.delegate = delegate;
        this.filterData = filterData;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack result = delegate.extract(amount, filterType);
        if (result.isEmpty()) return result;

        if (filterData != null && !ItemFilterComponent.allows(filterData, result)) {
            delegate.rollback(result);
            return ItemStack.EMPTY;
        }
        return result;
    }

    @Override
    public ItemStack simulateExtract(int amount) {
        ItemStack result = delegate.simulateExtract(amount);
        if (result.isEmpty()) return result;

        if (filterData != null && !ItemFilterComponent.allows(filterData, result)) {
            return ItemStack.EMPTY;
        }
        return result;
    }

    @Override
    public int insert(ItemStack stack) {
        if (filterData != null && !ItemFilterComponent.allows(filterData, stack)) {
            return 0;
        }
        return delegate.insert(stack);
    }

    @Override
    public int simulateInsert(ItemStack stack) {
        if (filterData != null && !ItemFilterComponent.allows(filterData, stack)) {
            return 0;
        }
        return delegate.simulateInsert(stack);
    }

    @Override
    public void rollback(ItemStack stack) {
        delegate.rollback(stack);
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean isFull() {
        return delegate.isFull();
    }

    @Override
    public void markTransferred() {
        delegate.markTransferred();
    }

    @Override
    public void sync() {
        delegate.sync();
    }

    @Override
    public SlotAccessor unwrap() {
        return delegate;
    }
}