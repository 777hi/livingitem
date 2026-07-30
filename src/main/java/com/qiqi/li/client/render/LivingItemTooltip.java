package com.qiqi.li.client.render;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;

@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public class LivingItemTooltip {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (!LivingItemManager.isLivingItem(stack)) return;

        Item.TooltipContext context = Item.TooltipContext.EMPTY;
        TooltipFlag flag = event.getFlags();
        Consumer<Component> tooltipAdder = event.getToolTip()::add;

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.title"));

        for (LivingItemFunction function : LivingItemManager.getAllFunctions()) {
            if (function.canApply(stack)) {
                function.addToTooltip(context, tooltipAdder, flag, stack);
            }
        }
    }
}