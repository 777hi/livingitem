package com.qiqi.li.living.domain.map;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

public final class MapCoordHelper {

    public static final int MAP_SIZE = 128;

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

    public static double[] uvToWorldPos(MapItemSavedData mapData, double u, double v) {
        int scale = 1 << mapData.scale;
        double worldX = mapData.centerX + (u * MAP_SIZE - 64) * scale;
        double worldZ = mapData.centerZ + (v * MAP_SIZE - 64) * scale;
        return new double[]{worldX, worldZ};
    }

    @Nullable
    public static MapBanner findBannerHit(MapItemSavedData mapData, int mapX, int mapY) {
        return findBannerHit(mapData, mapX, mapY, mapData.centerX, mapData.centerZ);
    }

    @Nullable
    private static MapBanner findBannerHit(MapItemSavedData mapData, int mapX, int mapY, int centerX, int centerZ) {
        var banners = mapData.getBanners();
        if (banners == null || banners.isEmpty()) return null;

        int scale = 1 << mapData.scale;
        double closestDistSq = Double.MAX_VALUE;
        MapBanner closest = null;

        for (MapBanner banner : banners) {
            int bannerPx = (banner.pos().getX() - centerX) / scale + 64;
            int bannerPy = (banner.pos().getZ() - centerZ) / scale + 64;

            int dx = mapX - bannerPx;
            int dy = mapY - bannerPy;
            double distSq = dx * dx + dy * dy;

            if (distSq <= HIT_RADIUS_SQ && distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = banner;
            }
        }

        return closest;
    }

    /**
     * 命中判定半径（地图像素），以平方值存储以避免开方。
     */
    private static final double HIT_RADIUS_SQ = 5.0 * 5.0;

    @Nullable
    public static MapDecoration findTargetPointHit(MapItemSavedData mapData, int mapX, int mapY) {
        double closestDistSq = Double.MAX_VALUE;
        MapDecoration closest = null;

        for (MapDecoration decoration : mapData.getDecorations()) {
            if (!isTeleportableType(decoration)) continue;

            float decoPixelX = (float) decoration.x() / 2.0F + 64.0F;
            float decoPixelY = (float) decoration.y() / 2.0F + 64.0F;

            float dx = mapX - decoPixelX;
            float dy = mapY - decoPixelY;
            double distSq = dx * dx + dy * dy;

            if (distSq <= HIT_RADIUS_SQ && distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = decoration;
            }
        }

        return closest;
    }

    public static boolean isTeleportableDecorationHit(MapItemSavedData mapData, int mapX, int mapY) {
        return findTargetPointHit(mapData, mapX, mapY) != null;
    }

    private static final Set<String> TELEPORTABLE_DECORATION_PATHS = Set.of(
        "player",
        "frame",
        "red_marker",
        "blue_marker",
        "target_x",
        "target_point",
        "red_x",
        "mansion",
        "monument",
        "jungle_temple",
        "swamp_hut",
        "trial_chambers",
        "village_desert",
        "village_plains",
        "village_savanna",
        "village_snowy",
        "village_taiga",
        "banner_white",
        "banner_orange",
        "banner_magenta",
        "banner_light_blue",
        "banner_yellow",
        "banner_lime",
        "banner_pink",
        "banner_gray",
        "banner_light_gray",
        "banner_cyan",
        "banner_purple",
        "banner_blue",
        "banner_brown",
        "banner_green",
        "banner_red",
        "banner_black"
    );

    private static boolean isBannerType(MapDecoration decoration) {
        return matchDecorationPath(decoration, path -> path.startsWith("banner_"));
    }

    private static boolean isTeleportableType(MapDecoration decoration) {
        return matchDecorationPath(decoration, TELEPORTABLE_DECORATION_PATHS::contains);
    }

    private static boolean matchDecorationPath(MapDecoration decoration, Predicate<String> predicate) {
        return decoration.type().unwrapKey()
            .map(key -> predicate.test(key.location().getPath()))
            .orElse(false);
    }

    public static double[] getTargetPointWorldPos(@Nullable ItemStack mapStack, MapItemSavedData mapData, MapDecoration targetDecoration) {
        if (mapStack != null) {
            MapDecorations mapDecorations = mapStack.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);
            for (MapDecorations.Entry entry : mapDecorations.decorations().values()) {
                if (isTeleportableEntry(entry)
                    && entry.type().equals(targetDecoration.type())
                    && entry.x() == targetDecoration.x()
                    && entry.z() == targetDecoration.y()) {
                    return new double[]{entry.x(), entry.z()};
                }
            }
            for (MapDecorations.Entry entry : mapDecorations.decorations().values()) {
                if (isTeleportableEntry(entry)) {
                    return new double[]{entry.x(), entry.z()};
                }
            }
        }

        int scale = 1 << mapData.scale;
        float pixelX = (float) targetDecoration.x() / 2.0F + 64.0F;
        float pixelY = (float) targetDecoration.y() / 2.0F + 64.0F;
        double worldX = mapData.centerX + (pixelX - 64.0F) * scale;
        double worldZ = mapData.centerZ + (pixelY - 64.0F) * scale;
        return new double[]{worldX, worldZ};
    }

    private static boolean isTeleportableEntry(MapDecorations.Entry entry) {
        return matchEntryPath(entry, TELEPORTABLE_DECORATION_PATHS::contains);
    }

    private static boolean matchEntryPath(MapDecorations.Entry entry, Predicate<String> predicate) {
        return entry.type().unwrapKey()
            .map(key -> predicate.test(key.location().getPath()))
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

    /**
     * 由玩家视角朝向推算地图上的目标点，服务端与客户端共用同一份公式。
     * <p>此方法是准心显示与实际传送落点的唯一真源，两端必须走这里，
     * 否则会出现"准心指向 A、传送到 B"的错位。
     *
     * @param scale         地图缩放倍数（{@code 1 << mapData.scale}）
     * @param centerX       地图中心世界 X（服务端取 mapData，客户端取同步来的元数据）
     * @param centerZ       地图中心世界 Z
     * @param sameDimension 玩家当前维度是否与地图记录的维度一致
     */
    private static TargetResult calcTarget(Player player, int scale, int centerX, int centerZ, boolean sameDimension) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        // 玩家在图内且同维度时以玩家为起点，否则退回地图中心
        int playerMapX = (int) ((player.getX() - centerX) / scale) + 64;
        int playerMapY = (int) ((player.getZ() - centerZ) / scale) + 64;
        boolean onMap = sameDimension
                && playerMapX >= 0 && playerMapX < MAP_SIZE
                && playerMapY >= 0 && playerMapY < MAP_SIZE;

        double originX = onMap ? player.getX() : centerX;
        double originZ = onMap ? player.getZ() : centerZ;

        double maxDist = calcMaxDistToMapEdge(originX, originZ, dx, dz, centerX, centerZ, scale);
        double distance = ((90.0 - pitch) / 90.0) * maxDist;
        distance = Math.max(0, Math.min(distance, maxDist));

        double targetWorldX = originX + dx * distance;
        double targetWorldZ = originZ + dz * distance;

        int targetMapX = (int) ((targetWorldX - centerX) / scale) + 64;
        int targetMapY = (int) ((targetWorldZ - centerZ) / scale) + 64;

        return new TargetResult(targetMapX, targetMapY, targetWorldX, targetWorldZ);
    }

    public static TargetResult getTargetFromYawPitch(MapItemSavedData mapData, ServerPlayer player) {
        return calcTarget(player, 1 << mapData.scale, mapData.centerX, mapData.centerZ,
                player.level().dimension() == mapData.dimension);
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

    public static int[] calcClientTarget(MapItemSavedData mapData, LivingMapClientCache.MapMetadata metadata, Player player) {
        TargetResult result = calcTarget(player, 1 << mapData.scale,
                metadata.centerX(), metadata.centerZ(),
                player.level().dimension() == metadata.dimension());
        return new int[]{result.mapX(), result.mapY()};
    }
}