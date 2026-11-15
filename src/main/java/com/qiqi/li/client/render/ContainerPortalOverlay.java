package com.qiqi.li.client.render;

import com.qiqi.li.client.util.ClientMenuSlots;
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
        int menuId = ClientMenuSlots.serverMenuId(screen.getMenu());
        if (menuId < 0) return;
        var snapshots = PortalClientCache.snapshots(menuId);
        if (snapshots.isEmpty()) return;
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(PORTAL);
        // Z 层级走统一协议（基座 + 350 + 禁深度），见 GuiOverlays
        GuiOverlays.paintOverContent(graphics, () -> {
            graphics.setColor(1, 1, 1, 0.55f);
            for (var slot : screen.getMenu().slots) {
                if (!ClientMenuSlots.isStorageSlot(screen.getMenu(), slot)) continue;
                int index = ClientMenuSlots.serverMenuSlot(screen.getMenu(), slot);
                for (var snapshot : snapshots) {
                    Integer logicalSlot = snapshot.menuSlots().get(index);
                    if (logicalSlot == null || !snapshot.cells().contains(logicalSlot)) continue;
                    graphics.blit(screen.getGuiLeft() + slot.x - 1, screen.getGuiTop() + slot.y - 1,
                        0, 18, 18, sprite);
                    break;
                }
            }
            graphics.setColor(1, 1, 1, 1);
        });
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?>) PortalClientCache.clear();
    }
}
