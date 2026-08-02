package com.qiqi.li.living.domain.map;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

public final class MapCoordHelper {

    private static final int MAP_SIZE = 128;

    private MapCoordHelper() {}

    public static HitResult hitVecToMapPixel(Vec3 hitVec, BlockPos framePos, Direction facing, int rotation) {
        double u = switch (facing) {
            case NORTH -> hitVec.x - framePos.getX();
            case SOUTH -> 1 - (hitVec.x - framePos.getX());
            case EAST  -> hitVec.z - framePos.getZ();
            case WEST  -> 1 - (hitVec.z - framePos.getZ());
            case DOWN  -> hitVec.x - framePos.getX();
            case UP    -> hitVec.x - framePos.getX();
            default    -> 0;
        };

        double v = switch (facing) {
            case NORTH -> 1 - (hitVec.y - framePos.getY());
            case SOUTH -> 1 - (hitVec.y - framePos.getY());
            case EAST  -> 1 - (hitVec.y - framePos.getY());
            case WEST  -> 1 - (hitVec.y - framePos.getY());
            case DOWN  -> hitVec.z - framePos.getZ();
            case UP    -> 1 - (hitVec.z - framePos.getZ());
            default    -> 0;
        };

        if (facing == Direction.DOWN || facing == Direction.UP) {
            v = 1 - v;
        } else {
            u = 1 - u;
        }

        int rot = rotation % 4;

        double rotatedU = switch (rot) {
            case 1 -> v;
            case 2 -> 1 - u;
            case 3 -> 1 - v;
            default -> u;
        };

        double rotatedV = switch (rot) {
            case 1 -> 1 - u;
            case 2 -> 1 - v;
            case 3 -> u;
            default -> v;
        };

        return new HitResult(rotatedU, rotatedV);
    }

    public static int uvToMapX(double u) {
        return (int) (u * MAP_SIZE);
    }

    public static int uvToMapY(double v) {
        return (int) (v * MAP_SIZE);
    }

    public static boolean isExplored(MapItemSavedData mapData, int mapX, int mapY) {
        if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) return false;
        return mapData.colors[mapY * MAP_SIZE + mapX] != 0;
    }

    public static BlockPos mapPixelToWorld(MapItemSavedData mapData, int mapX, int mapY) {
        int scale = 1 << mapData.scale;
        int worldX = mapData.centerX + (mapX - 64) * scale;
        int worldZ = mapData.centerZ + (mapY - 64) * scale;
        return new BlockPos(worldX, 0, worldZ);
    }

    @Nullable
    public static MapBanner findBannerHit(MapItemSavedData mapData, int mapX, int mapY) {
        return findBannerHit(mapData, mapX, mapY, mapData.centerX, mapData.centerZ);
    }

    @Nullable
    public static MapBanner findBannerHit(MapItemSavedData mapData, int mapX, int mapY, int centerX, int centerZ) {
        var banners = mapData.getBanners();
        if (banners == null || banners.isEmpty()) return null;

        int scale = 1 << mapData.scale;
        double closestDist = Double.MAX_VALUE;
        MapBanner closest = null;

        for (MapBanner banner : banners) {
            int bannerPx = (banner.pos().getX() - centerX) / scale + 64;
            int bannerPy = (banner.pos().getZ() - centerZ) / scale + 64;

            int dx = mapX - bannerPx;
            int dy = mapY - bannerPy;
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist <= 5 && dist < closestDist) {
                closestDist = dist;
                closest = banner;
            }
        }

        return closest;
    }

    @Nullable
    public static MapDecoration findTargetPointHit(MapItemSavedData mapData, int mapX, int mapY) {
        double closestDist = Double.MAX_VALUE;
        MapDecoration closest = null;

        for (MapDecoration decoration : mapData.getDecorations()) {
            if (!isRedXType(decoration)) continue;

            float decoPixelX = (float) decoration.x() / 2.0F + 64.0F;
            float decoPixelY = (float) decoration.y() / 2.0F + 64.0F;

            float dx = mapX - decoPixelX;
            float dy = mapY - decoPixelY;
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist <= 5.0 && dist < closestDist) {
                closestDist = dist;
                closest = decoration;
            }
        }

        return closest;
    }

    public static boolean isBannerDecorationHit(MapItemSavedData mapData, int mapX, int mapY) {
        double closestDist = Double.MAX_VALUE;
        boolean found = false;

        for (MapDecoration decoration : mapData.getDecorations()) {
            if (!isBannerType(decoration)) continue;

            float decoPixelX = (float) decoration.x() / 2.0F + 64.0F;
            float decoPixelY = (float) decoration.y() / 2.0F + 64.0F;

            float dx = mapX - decoPixelX;
            float dy = mapY - decoPixelY;
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist <= 5.0 && dist < closestDist) {
                closestDist = dist;
                found = true;
            }
        }

        return found;
    }

    private static boolean isBannerType(MapDecoration decoration) {
        return decoration.type().unwrapKey()
            .map(key -> key.location().getPath().startsWith("banner_"))
            .orElse(false);
    }

    private static boolean isRedXType(MapDecoration decoration) {
        return decoration.type().unwrapKey()
            .map(key -> key.location().getPath().equals("red_x"))
            .orElse(false);
    }

    @Nullable
    public static double[] getTargetPointWorldPos(ItemStack mapStack, MapItemSavedData mapData, MapDecoration targetDecoration) {
        MapDecorations mapDecorations = mapStack.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);
        for (MapDecorations.Entry entry : mapDecorations.decorations().values()) {
            if (isRedXEntry(entry)) {
                return new double[]{entry.x(), entry.z()};
            }
        }

        int scale = 1 << mapData.scale;
        float pixelX = (float) targetDecoration.x() / 2.0F + 64.0F;
        float pixelY = (float) targetDecoration.y() / 2.0F + 64.0F;
        double worldX = mapData.centerX + (pixelX - 64.0F) * scale;
        double worldZ = mapData.centerZ + (pixelY - 64.0F) * scale;
        return new double[]{worldX, worldZ};
    }

    private static boolean isRedXEntry(MapDecorations.Entry entry) {
        return entry.type().unwrapKey()
            .map(key -> key.location().getPath().equals("red_x"))
            .orElse(false);
    }

    public static int getPlayerMapX(MapItemSavedData mapData, ServerPlayer player) {
        int scale = 1 << mapData.scale;
        return (int) ((player.getX() - mapData.centerX) / scale) + 64;
    }

    public static int getPlayerMapY(MapItemSavedData mapData, ServerPlayer player) {
        int scale = 1 << mapData.scale;
        return (int) ((player.getZ() - mapData.centerZ) / scale) + 64;
    }

    public static boolean isPlayerOnMap(MapItemSavedData mapData, ServerPlayer player) {
        int px = getPlayerMapX(mapData, player);
        int py = getPlayerMapY(mapData, player);
        return px >= 0 && px < MAP_SIZE && py >= 0 && py < MAP_SIZE;
    }

    public static ResourceKey<Level> getMapDimension(MapItemSavedData mapData) {
        return mapData.dimension;
    }

    public static TargetResult getTargetFromYawPitch(MapItemSavedData mapData, ServerPlayer player) {
        int scale = 1 << mapData.scale;

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        double originX, originZ;
        if (isPlayerOnMap(mapData, player) && player.level().dimension() == mapData.dimension) {
            originX = player.getX();
            originZ = player.getZ();
        } else {
            originX = mapData.centerX;
            originZ = mapData.centerZ;
        }

        double maxDist = calcMaxDistToMapEdge(originX, originZ, dx, dz, mapData.centerX, mapData.centerZ, scale);
        double distance = ((90.0 - pitch) / 90.0) * maxDist;
        distance = Math.max(0, Math.min(distance, maxDist));

        double targetWorldX = originX + dx * distance;
        double targetWorldZ = originZ + dz * distance;

        int targetMapX = (int) ((targetWorldX - mapData.centerX) / scale) + 64;
        int targetMapY = (int) ((targetWorldZ - mapData.centerZ) / scale) + 64;

        return new TargetResult(targetMapX, targetMapY, targetWorldX, targetWorldZ);
    }

    public static int calculateMapCenterCoord(double playerCoord, int scale) {
        int gridSize = 128 * (1 << scale);
        return (int) (Math.floor(playerCoord / gridSize) * gridSize + gridSize / 2);
    }

    public static double calcMaxDistToMapEdge(double originX, double originZ, double dx, double dz, int centerX, int centerZ, int scale) {
        double mapMinX = centerX - 64.0 * scale;
        double mapMaxX = centerX + 64.0 * scale;
        double mapMinZ = centerZ - 64.0 * scale;
        double mapMaxZ = centerZ + 64.0 * scale;

        double maxDist = Double.MAX_VALUE;

        if (dx > 1e-9) {
            maxDist = Math.min(maxDist, (mapMaxX - originX) / dx);
        } else if (dx < -1e-9) {
            maxDist = Math.min(maxDist, (mapMinX - originX) / dx);
        }

        if (dz > 1e-9) {
            maxDist = Math.min(maxDist, (mapMaxZ - originZ) / dz);
        } else if (dz < -1e-9) {
            maxDist = Math.min(maxDist, (mapMinZ - originZ) / dz);
        }

        if (maxDist < 0 || maxDist == Double.MAX_VALUE) {
            maxDist = scale * 64.0;
        }

        return maxDist;
    }

    public record HitResult(double u, double v) {}

    public record TargetResult(int mapX, int mapY, double worldX, double worldZ) {}
}