package com.qiqi.li.testutil;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.container.ContainerContext;

/**
 * 内存容器上下文 —— 单元测试用的 {@link ContainerContext} 替身。
 *
 * <p>用简单的 {@code ItemStack} 数组模拟容器，不依赖 Level、BlockEntity
 * 或 IItemHandler，使纯逻辑（红石传播、槽位解析等）可以脱离游戏环境测试。</p>
 *
 * <p>用法：
 * <pre>
 * FakeContainerContext ctx = new FakeContainerContext(27, 9);
 * ctx.set(4, new ItemStack(Items.REDSTONE));
 * </pre>
 */
public class FakeContainerContext implements ContainerContext {

    private final ItemStack[] slots;
    private final int width;
    private final String containerKey;

    /** 记录所有 syncSlotToClients 调用的槽位，供断言同步行为 */
    public final List<Integer> syncedSlots = new ArrayList<>();

    public FakeContainerContext(int size, int width) {
        this(size, width, "test_container");
    }

    public FakeContainerContext(int size, int width, String containerKey) {
        this.slots = new ItemStack[size];
        this.width = width;
        this.containerKey = containerKey;
        for (int i = 0; i < size; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }

    /** 放入物品并返回 this，便于链式构建测试场景 */
    public FakeContainerContext set(int slot, ItemStack stack) {
        slots[slot] = stack;
        return this;
    }

    @Override
    public int getSize() {
        return slots.length;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        if (logicalSlot < 0 || logicalSlot >= slots.length) return ItemStack.EMPTY;
        return slots[logicalSlot];
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        if (logicalSlot < 0 || logicalSlot >= slots.length) return;
        slots[logicalSlot] = stack;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public String getContainerKey() {
        return containerKey;
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "_slot_" + logicalSlot + "_func_" + functionId;
    }

    @Override
    public void syncSlotToClients(int logicalSlot, ItemStack stack) {
        syncedSlots.add(logicalSlot);
    }
}
