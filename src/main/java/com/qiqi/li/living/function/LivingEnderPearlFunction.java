package com.qiqi.li.living.function;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.data.LivingEnderPearlData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class LivingEnderPearlFunction implements LivingItemFunction {

    public static final String ID = "living_ender_pearl";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.ENDER_PEARL) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        if (level.isClientSide) return;

        for (SlotEntry entry : entries) {
            ItemStack stack = container.getItem(entry.slotIndex());
            LivingEnderPearlData data = LivingItemManager.getEnderPearlData(stack);
            if (data.cooldown() > 0) {
                LivingItemManager.setEnderPearlData(stack, data.withCooldown(data.cooldown() - 1));
                container.syncSlotToClients(entry.slotIndex(), stack);
            }
        }
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingEnderPearlData data = LivingItemManager.getEnderPearlData(stack);
        if (data.cooldown() > 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_pearl.cooldown", data.cooldown())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_pearl.ready")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of(LivingItemManager.LIVING_ENDER_PEARL_DATA.value());
    }

    public static boolean isLivingEnderPearl(ItemStack stack) {
        return stack.is(Items.ENDER_PEARL) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean isOnCooldown(ItemStack stack) {
        return LivingItemManager.getEnderPearlData(stack).cooldown() > 0;
    }

    public static void setCooldown(ItemStack stack, int cooldown) {
        LivingItemManager.setEnderPearlData(stack, LivingEnderPearlData.DEFAULT.withCooldown(cooldown));
    }
}