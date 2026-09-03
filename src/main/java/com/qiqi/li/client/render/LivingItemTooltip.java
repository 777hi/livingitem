package com.qiqi.li.client.render;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
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
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;

@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public class LivingItemTooltip {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (!LivingItemManager.isLivingItem(stack)) return;

        // 从当前屏幕获取悬停槽位，查找对应的运行时数据（用于 tooltip 渲染）
        LivingItemRuntimeData runtimeData = LivingItemRuntimeData.EMPTY;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            Slot hoveredSlot = containerScreen.getSlotUnderMouse();
            if (hoveredSlot != null && hoveredSlot.getItem() == stack) {
                runtimeData = LivingItemClientCache.get(hoveredSlot.getContainerSlot());
            }
        }
        LivingItemClientCache.setCurrentTooltipData(runtimeData);
        try {
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
        } finally {
            LivingItemClientCache.clearCurrentTooltipData();
        }
    }
}