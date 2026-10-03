package com.qiqi.li.living.container;

import java.util.Arrays;

/**
 * 容器级数据的统一存储 —— 用 {@link ContainerDataKey#index()} 做<b>数组下标</b>。
 *
 * <p>替代原先「fluid / redstone / power 三个硬编码字段」的写法：
 * 新增一种容器级数据 = 定义一个 {@link ContainerDataKey} 常量，本类与调用方<b>零改动</b>。</p>
 *
 * <p>访问是纯数组读写（O(1)，无哈希），与旧字段访问同价。</p>
 */
public final class ContainerDataStore {

    private Object[] slots = new Object[8];
    private int count;

    /** 查询但不创建；不存在返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    public <T> T peek(ContainerDataKey<T> key) {
        int i = key.index();
        return i < slots.length ? (T) slots[i] : null;
    }

    /** 查询或创建（用 key 的工厂）。 */
    public <T> T getOrCreate(ContainerDataKey<T> key) {
        T v = peek(key);
        if (v == null) {
            v = key.create();
            put(key, v);
        }
        return v;
    }

    /** 写入（传 {@code null} 等价于移除）。 */
    public <T> void put(ContainerDataKey<T> key, T value) {
        int i = key.index();
        if (i >= slots.length) {
            slots = Arrays.copyOf(slots, Math.max(i + 1, slots.length * 2));
        }
        Object old = slots[i];
        slots[i] = value;
        if (value != null && old == null) count++;
        if (value == null && old != null) count--;
    }

    /** 是否一个数据都没有（替代旧的 {@code hasData()}）。 */
    public boolean isEmpty() { return count == 0; }

    /** 清空全部。 */
    public void clear() {
        Arrays.fill(slots, null);
        count = 0;
    }
}
