package com.qiqi.li.living.domain.redstone;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public class LivingLeverFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_lever";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.LEVER) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingLeverData data = LivingItemManager.getLeverData(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.lever.title"));

        if (data.powered()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.lever.powered")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.lever.unpowered")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.lever.max_signal")
            .append(Component.literal(": " + ContainerRedstoneData.getSignalCap(stack.getCount())))
            .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerRedstoneData redstoneData = tick.redstoneData;
        if (redstoneData == null) {
            redstoneData = new ContainerRedstoneData(ctx.getSize());
            tick.redstoneData = redstoneData;
        }
        redstoneData.calculate(ctx, tick);
    }

    public static boolean toggleLever(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        LivingLeverData data = LivingItemManager.getLeverData(stack);
        LivingItemManager.setLeverData(stack, data.withPowered(!data.powered()));
        return true;
    }
}