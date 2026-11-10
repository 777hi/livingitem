package com.qiqi.li.client.render.tlm;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** TLM客户端联动的可选启动入口。 */
@EventBusSubscriber(modid = "living_item", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LivingSoulSlabSlotPreview {
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
}
