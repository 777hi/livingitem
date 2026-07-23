package com.qiqi.li.living.core.accessor;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.ItemFilterComponent;

/**
 * 过滤装饰器 —— 为任意 SlotAccessor 添加黑白名单过滤。
 *
 * <h2>设计目的</h2>
 * <p>
 * 将所有活漏斗主导的传输的黑白名单过滤逻辑集中到一处，
 * 避免在每个 SlotAccessor 实现中重复编写过滤代码。
 * </p>
 *
 * <h2>工作原理</h2>
 * <ul>
 *   <li><strong>extract()</strong>：提取后检查物品是否通过过滤，不通过则退回</li>
 *   <li><strong>insert()</strong>：插入前检查物品是否通过过滤，不通过则拒绝</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>
 * SlotAccessor raw = new PlainSlotAccessor(...);
 * SlotAccessor filtered = new FilteredSlotAccessor(raw, filterState);
 * </pre>
 *
 * <p>如果 filterState 为 null，则直接委托给原始 Accessor，不添加过滤。</p>
 */
public class FilteredSlotAccessor implements SlotAccessor {

    private final SlotAccessor delegate;
    private final ComponentState filterState;

    /**
     * 创建过滤装饰器。
     *
     * @param delegate 原始 SlotAccessor
     * @param filterState 过滤组件状态（null 表示不过滤）
     */
    public FilteredSlotAccessor(SlotAccessor delegate, ComponentState filterState) {
        this.delegate = delegate;
        this.filterState = filterState;
    }

    @Override
    public ItemStack extract(int amount, ItemStack filterType) {
        ItemStack result = delegate.extract(amount, filterType);
        if (result.isEmpty()) return result;

        // 提取后检查是否通过过滤
        if (filterState != null && !ItemFilterComponent.allows(filterState, result)) {
            // 不通过过滤，退回物品
            delegate.rollback(result);
            return ItemStack.EMPTY;
        }
        return result;
    }

    @Override
    public int insert(ItemStack stack) {
        // 插入前检查是否通过过滤
        if (filterState != null && !ItemFilterComponent.allows(filterState, stack)) {
            return 0; // 拒绝插入
        }
        return delegate.insert(stack);
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
}