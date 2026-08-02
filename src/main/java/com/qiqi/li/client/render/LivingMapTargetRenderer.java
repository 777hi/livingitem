package com.qiqi.li.client.render;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.map.LivingMapClientCache;
import com.qiqi.li.living.domain.map.MapCoordHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * 在手持活地图时，渲染传送目标标记（红色十字准星+边框）。
 * <p>
 * 标记位置由玩家视角 yaw/pitch 计算得出，与服务器端传送逻辑一致。
 * 已探索区域显示绿色边框，未探索区域显示红色边框。
 */
public class LivingMapTargetRenderer {

    private static final int MAP_SIZE = 128;
    private static final int MARKER_SIZE = 5;
    private static final int COLOR_RED = 0xFFFF3333;

    public static void register(RegisterGuiLayersEvent event) {
        // GUI overlay disabled in favor of ItemInHandRendererMixin
        // which renders the marker in the correct 3D coordinate system.
        // event.registerAbove(VanillaGuiLayers.CROSSHAIR, LivingItem.id("living_map_target"), LivingMapTargetRenderer::renderOverlay);
    }

    private static void renderOverlay(GuiGraphics gui, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        ItemStack mapStack = getHeldLivingMap(mc.player);
        if (mapStack == null) return;

        MapId mapId = mapStack.get(DataComponents.MAP_ID);
        if (mapId == null) return;

        MapItemSavedData mapData = MapItem.getSavedData(mapId, mc.level);
        if (mapData == null) return;

        int[] target = calcTargetMapPixel(mapId, mapData, mc);
        if (target == null) return;

        int targetMapX = target[0];
        int targetMapY = target[1];

        if (targetMapX < 0 || targetMapX >= MAP_SIZE || targetMapY < 0 || targetMapY >= MAP_SIZE) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // 与 ItemInHandRenderer 中的地图渲染位置对齐：
        // 地图纹理 128x128，渲染在屏幕中央
        float mapScreenSize = Math.min(screenWidth, screenHeight) * 0.75f;
        float pixelScale = mapScreenSize / MAP_SIZE;

        float mapScreenX = (screenWidth - mapScreenSize) / 2f;
        float mapScreenY = (screenHeight - mapScreenSize) / 2f;

        float targetScreenX = mapScreenX + targetMapX * pixelScale;
        float targetScreenY = mapScreenY + targetMapY * pixelScale;

        boolean explored = isExplored(mapData, targetMapX, targetMapY);
        renderTargetMarker(gui, targetScreenX, targetScreenY, explored);
    }

    private static void renderTargetMarker(GuiGraphics gui, float x, float y, boolean explored) {
        int ix = (int) x;
        int iy = (int) y;

        // 红色十字准星
        gui.fill(ix - MARKER_SIZE, iy, ix + MARKER_SIZE + 1, iy + 1, COLOR_RED);
        gui.fill(ix, iy - MARKER_SIZE, ix + 1, iy + MARKER_SIZE + 1, COLOR_RED);

        // 十字中心加粗
        gui.fill(ix - 1, iy - 1, ix + 2, iy + 2, COLOR_RED);

        // 外框：已探索=绿色，未探索=红色
        if (explored) {
            int r = MARKER_SIZE + 2;
            gui.renderOutline(ix - r, iy - r, r * 2 + 1, r * 2 + 1, 0x8800FF00);
        } else {
            int r = MARKER_SIZE + 1;
            gui.renderOutline(ix - r, iy - r, r * 2 + 1, r * 2 + 1, 0x88FF0000);
        }
    }

    /**
     * 根据玩家视角 yaw/pitch 计算目标在地图上的像素坐标。
     * 与服务器端 MapCoordHelper.getTargetFromYawPitch 逻辑完全一致。
     */
    private static int[] calcTargetMapPixel(MapId mapId, MapItemSavedData mapData, Minecraft mc) {
        int scale = 1 << mapData.scale;

        LivingMapClientCache.MapMetadata metadata = LivingMapClientCache.get(mapId.id());
        int centerX;
        int centerZ;
        boolean sameDimension;

        if (metadata != null) {
            centerX = metadata.centerX();
            centerZ = metadata.centerZ();
            sameDimension = mc.player.level().dimension() == metadata.dimension();
        } else {
            centerX = MapCoordHelper.calculateMapCenterCoord(mc.player.getX(), mapData.scale);
            centerZ = MapCoordHelper.calculateMapCenterCoord(mc.player.getZ(), mapData.scale);
            sameDimension = true;
        }

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();

        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        double originX, originZ;
        if (sameDimension) {
            int playerMapX = (int) ((mc.player.getX() - centerX) / scale) + 64;
            int playerMapY = (int) ((mc.player.getZ() - centerZ) / scale) + 64;
            boolean onMap = playerMapX >= 0 && playerMapX < MAP_SIZE && playerMapY >= 0 && playerMapY < MAP_SIZE;

            if (onMap) {
                originX = mc.player.getX();
                originZ = mc.player.getZ();
            } else {
                originX = centerX;
                originZ = centerZ;
            }
        } else {
            originX = centerX;
            originZ = centerZ;
        }

        double maxDist = MapCoordHelper.calcMaxDistToMapEdge(originX, originZ, dx, dz, centerX, centerZ, scale);
        double distance = ((90.0 - pitch) / 90.0) * maxDist;
        distance = Math.max(0, Math.min(distance, maxDist));

        double targetWorldX = originX + dx * distance;
        double targetWorldZ = originZ + dz * distance;

        int targetMapX = (int) ((targetWorldX - centerX) / scale) + 64;
        int targetMapY = (int) ((targetWorldZ - centerZ) / scale) + 64;

        return new int[]{targetMapX, targetMapY};
    }

    private static boolean isExplored(MapItemSavedData mapData, int mapX, int mapY) {
        if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) return false;
        return mapData.colors[mapY * MAP_SIZE + mapX] != 0;
    }

    private static ItemStack getHeldLivingMap(net.minecraft.world.entity.player.Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (isLivingMap(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (isLivingMap(offHand)) return offHand;
        return null;
    }

    private static boolean isLivingMap(ItemStack stack) {
        return stack.is(Items.FILLED_MAP) && LivingItemManager.isLivingItem(stack);
    }
}