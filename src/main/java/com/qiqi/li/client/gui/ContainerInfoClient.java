package com.qiqi.li.client.gui;

import com.qiqi.li.network.ContainerMenuInfoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** 回包只交给当前界面的按钮实例，其会话自行验证关联编号。 */
@net.neoforged.fml.common.EventBusSubscriber(modid = "living_item", value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class ContainerInfoClient {
    private ContainerInfoClient() {}

    @net.neoforged.bus.api.SubscribeEvent
    public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        ContainerMenuInfoPacket.registerClientReceiver(packet -> {
            var mc = Minecraft.getInstance();
            if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
            for (var child : screen.children()) {
                if (child instanceof LivingButton button) button.acceptContainerInfo(packet);
            }
        });
    }
}
