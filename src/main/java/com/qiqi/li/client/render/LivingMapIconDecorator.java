package com.qiqi.li.client.render;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.client.IItemDecorator;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class LivingMapIconDecorator implements IItemDecorator {

    private static final int MAP_SIZE = 128;
    private static final int ICON_SIZE = 16;
    private static final int BORDER = 1;
    private static final ResourceLocation MAP_BORDER = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "textures/item/living_map_border.png");
    private static final Map<Integer, MapIconTexture> TEXTURE_CACHE = new HashMap<>();

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!isLivingMap(stack)) return false;

        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, mc.level);
        if (mapData == null) return false;

        MapIconTexture iconTexture = getOrCreateTexture(mapId.id(), mapData);
        if (iconTexture == null) return false;

        iconTexture.updateIfNeeded(mapData);

        guiGraphics.blit(iconTexture.location, xOffset, yOffset, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        guiGraphics.blit(MAP_BORDER, xOffset, yOffset, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        return true;
    }

    @Nullable
    private MapIconTexture getOrCreateTexture(int mapId, MapItemSavedData mapData) {
        return TEXTURE_CACHE.compute(mapId, (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            MapIconTexture tex = MapIconTexture.create(id, mapData);
            if (tex != null) {
                Minecraft.getInstance().getTextureManager().register(tex.location, tex.texture);
            }
            return tex;
        });
    }

    private static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack);
    }

    private static class MapIconTexture {
        final DynamicTexture texture;
        final ResourceLocation location;
        private int dataHash;

        MapIconTexture(DynamicTexture texture, ResourceLocation location, int dataHash) {
            this.texture = texture;
            this.location = location;
            this.dataHash = dataHash;
        }

        @Nullable
        static MapIconTexture create(int mapId, MapItemSavedData mapData) {
            DynamicTexture texture = new DynamicTexture(ICON_SIZE, ICON_SIZE, true);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "map_icon/" + mapId);
            int hash = computeHash(mapData);
            MapIconTexture iconTex = new MapIconTexture(texture, loc, hash);
            iconTex.uploadTexture(mapData);
            return iconTex;
        }

        void updateIfNeeded(MapItemSavedData mapData) {
            int newHash = computeHash(mapData);
            if (newHash != dataHash) {
                dataHash = newHash;
                uploadTexture(mapData);
            }
        }

        private void uploadTexture(MapItemSavedData mapData) {
            float scale = (float) MAP_SIZE / ICON_SIZE;

            for (int y = 0; y < ICON_SIZE; y++) {
                for (int x = 0; x < ICON_SIZE; x++) {
                    int srcX = (int) (x * scale);
                    int srcY = (int) (y * scale);
                    srcX = Math.min(srcX, MAP_SIZE - 1);
                    srcY = Math.min(srcY, MAP_SIZE - 1);

                    int colorIndex = srcY * MAP_SIZE + srcX;
                    int rgba = MapColor.getColorFromPackedId(mapData.colors[colorIndex]);
                    texture.getPixels().setPixelRGBA(x, y, rgba);
                }
            }
            texture.upload();
        }

        boolean isClosed() {
            return texture.getPixels() == null;
        }

        private static int computeHash(MapItemSavedData mapData) {
            int h = 1;
            for (int i = 0; i < mapData.colors.length; i += 8) {
                h = 31 * h + mapData.colors[i];
            }
            return h;
        }
    }
}