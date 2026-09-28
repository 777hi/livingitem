package com.qiqi.li.living.domain.chest;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record LivingChestTooltipComponent(List<ItemStack> items, int columns, int rows) implements TooltipComponent {
}