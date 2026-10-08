package com.qiqi.li.living.domain.chest;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * 活箱子悬浮提示的渲染组件。
 *
 * <p>承载箱子内容预览的物品列表与行列数，由客户端渲染为网格预览（{@code TooltipComponent}）。</p>
 */
public record LivingChestTooltipComponent(List<ItemStack> items, int columns, int rows) implements TooltipComponent {
}