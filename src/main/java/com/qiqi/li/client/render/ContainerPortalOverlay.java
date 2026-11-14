package com.qiqi.li.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qiqi.li.living.domain.portal.PortalClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** 原版动态门户纹理的二维门面；绘制与点击使用同一菜单映射。 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ContainerPortalOverlay {
    private static final ResourceLocation PORTAL = ResourceLocation.withDefaultNamespace("block/nether_portal");

    private ContainerPortalOverlay() {}

    @SubscribeEvent
    public static void onForeground(ContainerScreenEvent.Render.Foreground event) {
        if (!(event.getContainerScreen() instanceof AbstractContainerScreen<?> screen)) return;
        render(event.getGuiGraphics(), screen);
    }

    public static void render(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        int menuId = com.qiqi.li.client.util.PortalMenuSlots.serverMenuId(screen.getMenu());
        var snapshots = PortalClientCache.snapshots(menuId);
        if (snapshots.isEmpty()) return;
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(PORTAL);
        var pose = graphics.pose();
        pose.pushPose();
        pose.setIdentity();
        pose.translate(0, 0, 400);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1, 1, 1, 0.55f);
        for (var snapshot : snapshots) {
            for (var entry : snapshot.menuSlots().entrySet()) {
                if (!snapshot.cells().contains(entry.getValue())) continue;
                int index = entry.getKey();
                if (index < 0 || index >= screen.getMenu().slots.size()) continue;
                var slot = screen.getMenu().getSlot(index);
                if (!com.qiqi.li.client.util.PortalMenuSlots.isStorageSlot(screen.getMenu(), slot)
                        || com.qiqi.li.client.util.PortalMenuSlots.serverMenuSlot(screen.getMenu(), slot) != index) continue;
                graphics.blit(screen.getGuiLeft() + slot.x - 1, screen.getGuiTop() + slot.y - 1,
                    0, 18, 18, sprite);
            }
        }
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?>) PortalClientCache.clear();
    }
}
