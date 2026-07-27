package com.qiqi.li.living.container;

import net.minecraft.world.item.ItemStack;

/**
 * 槽位信息提供者 —— 提供槽位能力查询。
 *
 * <p>扩展 {@link LivingContainer}，增加槽位限制、物品验证、模拟插入等能力。
 * 适用于需要精确控制物品放置的功能（如熔炉、漏斗）。</p>
 *
 * <p>典型使用者：
 * <ul>
 *   <li>{@link com.qiqi.li.living.function.LivingFurnaceFunction} — 检查输出槽空间</li>
 *   <li>{@link com.qiqi.li.living.function.LivingHopperFunction} — 模拟插入目标槽位</li>
 * </ul>
 * </p>
 */
public interface SlotInfoProvider extends LivingContainer {

    /**
     * 获取指定槽位的最大堆叠上限。
     *
     * <p>对于 IItemHandler 容器（抽屉、精妙背包等），返回 handler.getSlotLimit(slot)，
     * 可能远超 64；对于传统 Container，回退到 {@link #getMaxStackSize()}。</p>
     *
     * @param slot 槽位索引
     * @return 该槽位的最大堆叠数
     */
    default int getSlotLimit(int slot) {
        return getMaxStackSize();
    }

    /**
     * 检查物品是否能放入指定槽位。
     *
     * <p>对于玩家盔甲槽位，非盔甲物品会返回 false，防止物品消失。</p>
     *
     * @param slot 槽位索引
     * @param stack 要检查的物品
     * @return 是否允许放入
     */
    default boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    /**
     * 模拟向指定槽位插入物品，返回实际可插入的数量。
     *
     * <p>委托给 handler.insertItem(slot, stack, true)，比 isItemValid 更全面，
     * 因为它会考虑槽位中已有物品的堆叠情况。</p>
     *
     * @param slot 槽位索引
     * @param stack 要插入的物品
     * @return 实际可插入的数量
     */
    default int simulateInsertItem(int slot, ItemStack stack) {
        int slotLimit = getSlotLimit(slot);
        int maxStack = Math.min(slotLimit, stack.getMaxStackSize());
        return Math.min(stack.getCount(), maxStack);
    }

    /**
     * 获取容器的列数（GUI 宽度）。
     *
     * <p>用于槽位解析时计算行列位置。标准容器为 9 列，
     * 模组容器可能有不同的列数（如 13 列）。</p>
     *
     * @return 容器的列数，默认 9
     */
    default int getWidth() {
        return 9;
    }
}