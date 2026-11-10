package com.qiqi.li.client.render.tlm;

import com.qiqi.li.living.util.StaticCacheRegistry;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 仅 TLM 存在时程序化注册；不参与自动订阅扫描。 */
public final class LivingSoulSlabClientRegistration {
    private static final SoulSlabPreviewCache CACHE = new SoulSlabPreviewCache();

    static {
        StaticCacheRegistry.onClientLogout(LivingSoulSlabClientRegistration::clear);
    }

    static void clear() {
        CACHE.clear();
    }

    private LivingSoulSlabClientRegistration() {}

    public static void register() {
        SoulSlabPreviewCache cache = CACHE;
        NeoForge.EVENT_BUS.addListener((ContainerScreenEvent.Render.Background event) ->
                cache.beginFrame(event.getContainerScreen(), event.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener((ContainerScreenEvent.Render.Foreground event) ->
                cache.render(event.getContainerScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> cache.tick());
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> cache.clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> cache.clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> cache.clear());
    }

    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> CACHE.clear());
    }
}
