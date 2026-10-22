package com.qiqi.li.living.container;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 「<b>物品形态容器</b>」的注册口（2026-10-22，活背包 / `D-container-13`）。
 *
 * <h3>为什么需要它</h3>
 * <p>物品形态容器（精妙背包 / 潜影盒）的内容不在 {@code ItemHandler.BLOCK} 能力面上
 * —— 它由各模组自己实现（精妙的 {@code BackpackInventoryHandler} 是标准
 * {@link IItemHandler}，但我们拿不到它）。核心不认识这些模组类型（`D-container-09`）
 * ⇒ 由<b>联动模块注册一个来源</b>，活背包功能只问来源要 handler。</p>
 *
 * <h3>两个问题分开答</h3>
 * <ul>
 *   <li>{@link #isContainerItem} —— 「这件物品是容器吗」（决定功能是否认领它）</li>
 *   <li>{@link #contentHandler} —— 「怎么读写它的内容」（决定 tick 时拿什么 handler）</li>
 * </ul>
 *
 * <p>⚠️ 返回的 handler 必须是<b>活视图</b>（每次读到的都是当前内容），不能是快照 ——
 * 活背包的 tick 要能真正改动里面的物品。</p>
 */
public final class ItemFormContainers {

    /** 一个「物品形态容器」来源（每个支持的模组注册一个）。 */
    public interface Source {
        /** 这件物品是不是本来源能处理的容器物品。 */
        boolean isContainerItem(ItemStack stack);

        /**
         * 取内容读写视图；拿不到返回 {@code null}（未初始化 / 结构变了）。
         *
         * <p>⚠️ 调用方每 tick 调一次，实现方应自己缓存（精妙 wrapper 自带缓存）。</p>
         */
        @Nullable
        IItemHandler contentHandler(ItemStack stack);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private ItemFormContainers() {}

    /** 注册来源。**调用点必须在联动模块的初始化里**（R4 的同类约束）。 */
    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** 找能处理这件物品的来源；没有返回 {@code null}（⇒ 功能不认领）。 */
    @Nullable
    public static Source find(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (Source source : SOURCES) {
            if (source.isContainerItem(stack)) return source;
        }
        return null;
    }

    /** 这件物品是不是物品形态容器。 */
    public static boolean isContainerItem(ItemStack stack) {
        return find(stack) != null;
    }

    /**
     * 测试用：清空注册。
     *
     * <p>public 是因为测试分散在多个包（{@code container} / {@code domain.backpack}），
     * 包级可见会让后者访问不到；与 {@code FluidFlowServerSync.clearForTest} 同款处理。
     * 生产代码不该调。</p>
     */
    public static void clearForTest() {
        SOURCES.clear();
    }
}
