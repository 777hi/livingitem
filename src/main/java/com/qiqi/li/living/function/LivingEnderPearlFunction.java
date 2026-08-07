package com.qiqi.li.living.function;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
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
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isLivingEnderPearl(ItemStack stack) {
        return stack.is(Items.ENDER_PEARL) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean isOnCooldown(ServerPlayer player) {
        return player.getCooldowns().isOnCooldown(Items.ENDER_PEARL);
    }

    public static void setCooldown(ServerPlayer player, int cooldown) {
        player.getCooldowns().addCooldown(Items.ENDER_PEARL, cooldown);
    }

    @Nullable
    public static ItemStack findInInventory(ServerPlayer player) {
        if (isOnCooldown(player)) return null;
        for (ItemStack stack : player.getInventory().items) {
            if (isLivingEnderPearl(stack)) {
                return stack;
            }
        }
        return null;
    }
}