package com.qiqi.li.living.domain.map;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public class LivingMapFunction implements LivingItemFunction {

    public static final String ID = "living_map";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.MAP) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        int count = stack.getCount();
        double distance = 128.0 * count * count;
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.map.range",
            Component.literal(String.format("%.0f", distance))));
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}