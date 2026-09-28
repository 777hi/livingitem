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

        // 从当前屏幕获取悬停槽位，查找对应的运行时数据（用于 tooltip 渲染）。
        // 玩家背包 GUI 的遥测存放在独立的 player 缓存（服务端背包包直发本人，
        // 与 BE 容器缓存分离避免串台，v19.1）。
        LivingItemRuntimeData runtimeData = LivingItemRuntimeData.EMPTY;
        Minecraft mc = Minecraft.getInstance();
        boolean playerGui = mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
            || mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            Slot hoveredSlot = containerScreen.getSlotUnderMouse();
            if (hoveredSlot != null && hoveredSlot.getItem() == stack) {
                runtimeData = playerGui
                    ? LivingItemClientCache.getPlayer(hoveredSlot.getContainerSlot())
                    : LivingItemClientCache.get(hoveredSlot.getContainerSlot());
            }
        }
        // 兜底：特殊 GUI（如创造模式物品栏 tab）的槽位索引与 Inventory 不对齐时，
        // 按物品引用在玩家背包中定位——玩家背包的 wrapper 索引 = Inventory 索引。
        if (runtimeData == LivingItemRuntimeData.EMPTY && mc.player != null) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i) == stack) {
                    runtimeData = LivingItemClientCache.getPlayer(i);
                    break;
                }
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