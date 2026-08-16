package com.qiqi.li.living.container;

import net.minecraft.world.item.ItemStack;

/**
 * 活容器基础接口 —— 提供物品读写能力。
 *
 * <p>这是容器能力体系的最小接口，所有其他容器接口都继承它。
 * 功能类如果只需要读写物品，依赖此接口即可。</p>
 *
 * <p>典型使用者：{@link com.qiqi.li.living.domain.chest.LivingChestFunction}</p>
 */
public interface LivingContainer {

    /**
     * 获取容器总槽位数。
     */
    int getSize();

    /**
     * 获取指定槽位的物品。
     *
     * @param logicalSlot 逻辑槽位索引
     * @return 槽位中的物品，空槽返回 {@link ItemStack#EMPTY}
     */
    ItemStack getItem(int logicalSlot);

    /**
     * 设置指定槽位的物品。
     *
     * @param logicalSlot 逻辑槽位索引
     * @param stack 要设置的物品
     */
    void setItem(int logicalSlot, ItemStack stack);

    /**
     * 获取容器的默认最大堆叠数。
     *
     * <p>对于传统容器，返回统一值；对于 IItemHandler 容器，
     * 回退到第一个槽位的限制。</p>
     *
     * @return 默认最大堆叠数
     */
    int getMaxStackSize();
}