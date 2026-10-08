package com.qiqi.li.living.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

/**
 * 「容器类活物品」谓词注册表 —— 由各领域注册，供框架判断某个活物品的槽位能否展开为虚拟存储。
 *
 * <p>设计动机（2026-10-08 计划：A 类「抽象缺失」）：{@code SlotAccessorFactory} /
 * {@code SlotInteractions} 原先<b>直接调</b> {@code LivingChestFunction.isLivingChest} /
 * {@code LivingEnderChestFunction.isLivingEnderChest} ⇒ {@code transfer}（L2）认识
 * {@code chest} / {@code ender} 领域（L3）。改为注册驱动后，框架只认谓词，
 * 领域在自己的 {@code *Registration} 里登记 —— 与 {@link SlotAccessorFactory} 的
 * Provider 注册、{@code SlotInteractions} 的交互注册同一套思路。</p>
 */
public final class ContainerLikeItems {

    private static final List<Predicate<ItemStack>> PREDICATES = new ArrayList<>();

    private ContainerLikeItems() {}

    /** 注册一个谓词（由领域在 {@code *Registration} 里调用）。 */
    public static void register(Predicate<ItemStack> predicate) {
        PREDICATES.add(predicate);
    }

    /**
     * 该活物品是否属于「容器类」（活箱子 / 活末影箱等，槽位可展开为虚拟存储）。
     *
     * <p>调用方通常已确认它是活物品；本方法只回答「是不是容器类」。</p>
     */
    public static boolean isContainerLike(ItemStack stack) {
        for (Predicate<ItemStack> p : PREDICATES) {
            if (p.test(stack)) return true;
        }
        return false;
    }
}
