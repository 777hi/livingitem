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

    /** 缩略图相对图标的内缩量，四边各露出 1px 羊皮纸底图 */
    private static final int INSET = 1;
    private static final int THUMB_SIZE = ICON_SIZE - INSET * 2;

    /**
     * 缩略图的 z 深度。
     *
     * <p>物品模型由 GuiGraphics.renderItem() 渲染在 z=150，
     * 底图羊皮纸不透明会遮挡缩略图，因此需要抬高到模型之上。
     */
    private static final int THUMB_Z = 200;

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

        // 底图羊皮纸由 living_map 模型提供（装饰器在物品模型之后绘制），此处只叠加缩略图
        guiGraphics.blit(iconTexture.location, xOffset + INSET, yOffset + INSET, THUMB_Z,
            0.0F, 0.0F, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE);
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
            DynamicTexture texture = new DynamicTexture(THUMB_SIZE, THUMB_SIZE, true);
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
            float scale = (float) MAP_SIZE / THUMB_SIZE;

            for (int y = 0; y < THUMB_SIZE; y++) {
                for (int x = 0; x < THUMB_SIZE; x++) {
                    int srcX = (int) (x * scale);
                    int srcY = (int) (y * scale);
                    srcX = Math.min(srcX, MAP_SIZE - 1);
                    srcY = Math.min(srcY, MAP_SIZE - 1);

                    int colorIndex = srcY * MAP_SIZE + srcX;
                    int abgr = MapColor.getColorFromPackedId(mapData.colors[colorIndex]);
                    if ((abgr >>> 24) == 0) {
                        abgr = ExpandedMapTexture.UNEXPLORED_ABGR;
                    }
                    texture.getPixels().setPixelRGBA(x, y, abgr);
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