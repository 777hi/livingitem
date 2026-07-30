package com.qiqi.li.living.domain.ender;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

public record LivingChestTooltipComponent(List<ItemStack> items, int columns, int rows) implements TooltipComponent {
}