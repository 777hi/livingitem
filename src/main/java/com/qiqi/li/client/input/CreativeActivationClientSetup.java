package com.qiqi.li.client.input;

import com.qiqi.li.LivingItem;
import com.qiqi.li.network.CarriedUpdatePacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** 创造虚拟光标回包的客户端接收器注册。 */
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeActivationClientSetup {
    private CreativeActivationClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        CarriedUpdatePacket.registerClientReceiver(CreativeActivationRequests::accept);
    }
}
