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
 *   <li>{@link com.qiqi.li.living.domain.furnace.LivingFurnaceFunction} — 检查输出槽空间</li>
 *   <li>{@link com.qiqi.li.living.domain.hopper.LivingHopperFunction} — 模拟插入目标槽位</li>
 * </ul>
 * </p>
 */
public interface SlotInfoProvider extends LivingContainer {

    /**
     * 获取指定槽位的最大堆叠上限。
     *
     * <p>对于 IItemHandler 容器（抽屉一类的第三方容器），返回 handler.getSlotLimit(slot)，
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
     * 获取容器的列数（GUI 宽度）—— **没有默认值，必须由实现者回答**。
     *
     * <p>用于槽位解析时计算行列位置（邻居判定 / 流体 / 应力 / 红石 / 信标列扫描 /
     * 漏斗传输网格全都要它，且都拿它当除数 ⇒ 见不变量 I-C11）。</p>
     *
     * <p>⭐ <b>为什么删掉原来的 {@code default 9}</b>（2026-10-25，S1）：
     * 那个默认值**从来没有任何实现者用到**（三个真实 ctx 全部覆写了它），
     * 却是一颗雷：9 是“看着对”的值 ⇒ 将来哪个新容器上下文忘了覆写，
     * 不会编译报错、不会运行时报错，只会**静默地按 9 列解析**
     * —— 而列数错了的表现是“搬错格子但仍在搬”这类隐形错，极难查。
     * 改成抽象方法后，漏实现是<b>编译期错误</b>。</p>
     *
     * <p>生产容器的布局选择见 {@link ContainerLayoutResolver}：实例来源、方块规则与学习、
     * 内建约定、启发式；来源与列数由同一次解析产生。</p>
     *
     * @return 容器的列数（≥ 1，**不得为 0** —— 它是除数）
     */
    int getWidth();

    /** 未声明来源的实现仍保留其列数，但不得被 UI 当作已核验来源。 */
    default ContainerLayout getLayout() {
        return new ContainerLayout(getWidth(), ContainerLayout.Source.UNKNOWN);
    }
}