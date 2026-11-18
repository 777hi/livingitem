package com.qiqi.li.client.input;

import java.util.List;

import javax.annotation.Nullable;

import com.qiqi.li.client.util.ClientMenuSlots;
import com.qiqi.li.living.domain.tools.AxeStripHandler;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class AxeStripHint {
    private AxeStripHint() {}

    @Nullable
    public static List<Component> getHintLines(@Nullable AbstractContainerMenu menu, @Nullable Slot hoveredSlot) {
        if (menu == null || hoveredSlot == null || menu.getCarried().isEmpty()
                || !GuiInteractionHelper.supportsAxeStrip(menu, hoveredSlot)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != menu || mc.player.containerMenu != menu
                || !menu.stillValid(mc.player)) return null;
        int menuSlot = ClientMenuSlots.serverMenuSlot(menu, hoveredSlot);
        if (menuSlot < 0) return null;
        AbstractContainerMenu actualMenu = screen instanceof CreativeModeInventoryScreen
            ? mc.player.inventoryMenu : menu;
        if (!actualMenu.stillValid(mc.player)) return null;
        Slot targetSlot = actualMenu.slots.get(menuSlot);
        if (!targetSlot.mayPickup(mc.player)) return null;
        ItemStack target = targetSlot.getItem();
        List<Component> lines = tryBuildLines(menu.getCarried(), target, mc.player.isCreative());
        if (lines == null) return null;
        ItemStack result = AxeStripHandler.strippedResult(target);
        return !result.isEmpty() && targetSlot.mayPlace(result) ? lines : null;
    }

    @Nullable
    public static List<Component> tryBuildLines(ItemStack carried, ItemStack target, boolean creative) {
        if (carried.isEmpty() || target.isEmpty()) return null;
        ItemStack singleTool = carried.getCount() > 1 ? carried.copyWithCount(1) : carried;
        InteractionEntry entry = InteractionRegistry.findInteraction(singleTool, target, 1, false);
        if (entry == null || !AxeStripHandler.ACTION_ID.equals(entry.actionId())
                || !AxeStripHandler.canStripWith(singleTool, target)) return null;
        if (carried.getCount() > 1) {
            return List.of(Component.translatable("hint.livingitem.axe_strip.split"));
        }
        ItemStack result = AxeStripHandler.strippedResult(target);
        if (result.isEmpty()) return null;
        return List.of(
            Component.translatable("hint.livingitem.axe_strip.result", result.getHoverName(), result.getCount()),
            creative ? Component.translatable("hint.livingitem.axe_strip.cost_creative")
                : Component.translatable("hint.livingitem.axe_strip.cost", 1));
    }
}
