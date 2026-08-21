package com.qiqi.li.client.render;

import com.qiqi.li.living.domain.map.MapCoordHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public class ExpandedMapTexture {

    private static final int TEX_SIZE = 128;

    /**
     * 未探索区域的填充色（羊皮纸色），取自原版 map_background.png 的内芯色 RGB(214,190,150)。
     *
     * <p>未探索像素的 MapColor.NONE 返回 0（全透明），而 GuiGraphics.blit() 不启用混合，
     * 透明像素会被直接写成黑色，因此需要在上传纹理时替换为羊皮纸色。
     *
     * <p>NativeImage.setPixelRGBA() 实际接收 ABGR 排列，与 MapColor.calculateRGBColor() 一致。
     */
    public static final int UNEXPLORED_ABGR = 0xFF96BED6;

    public final DynamicTexture texture;
    public final ResourceLocation location;
    private int dataHash;

    ExpandedMapTexture(DynamicTexture texture, ResourceLocation location, int dataHash) {
        this.texture = texture;
        this.location = location;
        this.dataHash = dataHash;
    }

    public static ExpandedMapTexture create(int mapId, MapItemSavedData mapData) {
        DynamicTexture texture = new DynamicTexture(TEX_SIZE, TEX_SIZE, true);
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("living_item", "map_expanded/" + mapId);
        int hash = computeHash(mapData);
        ExpandedMapTexture tex = new ExpandedMapTexture(texture, loc, hash);
        tex.uploadTexture(mapData);
        return tex;
    }

    public void updateIfNeeded(MapItemSavedData mapData) {
        int newHash = computeHash(mapData);
        if (newHash != dataHash) {
            dataHash = newHash;
            uploadTexture(mapData);
        }
    }

    private void uploadTexture(MapItemSavedData mapData) {
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int colorIndex = y * MapCoordHelper.MAP_SIZE + x;
                int abgr = MapColor.getColorFromPackedId(mapData.colors[colorIndex]);
                if ((abgr >>> 24) == 0) {
                    abgr = UNEXPLORED_ABGR;
                }
                texture.getPixels().setPixelRGBA(x, y, abgr);
            }
        }
        texture.upload();
    }

    public boolean isClosed() {
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