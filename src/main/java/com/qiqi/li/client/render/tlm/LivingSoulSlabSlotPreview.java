package com.qiqi.li.client.render.tlm;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** 槽位图标的客户端接缝；未安装联动时不链接第三方实现。 */
@EventBusSubscriber(modid = "living_item", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LivingSoulSlabSlotPreview {
    @FunctionalInterface
    interface Preparation {
        boolean prepare(AbstractContainerScreen<?> screen, GuiGraphics graphics, ItemStack stack,
                        Slot slot, int x, int y, int seed, String countString);
    }

    private static Preparation preparation;

    private LivingSoulSlabSlotPreview() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("touhou_little_maid")) {
            event.enqueueWork(LivingSoulSlabClientRegistration::register);
        }
    }

    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        if (ModList.get().isLoaded("touhou_little_maid")) {
            LivingSoulSlabClientRegistration.registerReloadListener(event);
        }
    }

    static void install(Preparation callback) {
        preparation = callback;
    }

    public static boolean prepare(AbstractContainerScreen<?> screen, GuiGraphics graphics, ItemStack stack,
                                  Slot slot, int x, int y, int seed, String countString) {
        return preparation != null && preparation.prepare(screen, graphics, stack, slot, x, y, seed, countString);
    }
}
