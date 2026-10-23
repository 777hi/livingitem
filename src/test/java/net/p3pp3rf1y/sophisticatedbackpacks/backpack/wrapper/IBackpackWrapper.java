package net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper;

import net.minecraft.world.item.ItemStack;

/**
 * 测试替身：精妙背包包装器接口（2026-10-23）。
 *
 * <p>反射链只用到 {@link #getBackpack()}（「GUI 正在看的那个物品栈」）——
 * 与真实 {@code IBackpackWrapper} 的同形方法。</p>
 */
public interface IBackpackWrapper {

    ItemStack getBackpack();
}
