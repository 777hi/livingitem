package com.qiqi.li.living.domain.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.DimensionTransition;
import com.qiqi.li.living.compat.sable.ModSable;
import com.qiqi.li.living.function.LivingEnderPearlFunction;

import java.util.ArrayList;
import java.util.List;

public final class TeleportHelper {

    public static final int COOLDOWN_TICKS = 40;
    public static final float FALL_DAMAGE = 5.0f;

    private TeleportHelper() {}

    public static boolean teleportToMapPosition(ServerPlayer player, ServerLevel sourceLevel,
                                                 ServerLevel targetLevel,
                                                 double worldX, double worldZ,
                                                 ItemStack pearlStack, boolean unexplored) {
        if (LivingEnderPearlFunction.isOnCooldown(player)) return false;

        BlockPos targetPos = BlockPos.containing(worldX, player.getY(), worldZ);
        int safeY = findSafeY(targetLevel, targetPos);
        if (safeY < targetLevel.getMinBuildHeight()) return false;

        double destX = worldX + 0.5;
        double destY = safeY + 1.0;
        double destZ = worldZ + 0.5;

        return executeTeleport(player, sourceLevel, targetLevel, destX, destY, destZ, pearlStack, unexplored);
    }

    public static boolean teleportToBanner(ServerPlayer player, ServerLevel sourceLevel,
                                            ServerLevel targetLevel,
                                            BlockPos bannerPos,
                                            ItemStack pearlStack, boolean unexplored) {
        if (LivingEnderPearlFunction.isOnCooldown(player)) return false;

        double destX = bannerPos.getX() + 0.5;
        double destY = bannerPos.getY() + 1.0;
        double destZ = bannerPos.getZ() + 0.5;

        return executeTeleport(player, sourceLevel, targetLevel, destX, destY, destZ, pearlStack, unexplored);
    }

    private static boolean executeTeleport(ServerPlayer player, ServerLevel sourceLevel,
                                            ServerLevel targetLevel,
                                            double destX, double destY, double destZ,
                                            ItemStack pearlStack, boolean unexplored) {
        boolean crossDim = player.level().dimension() != targetLevel.dimension();

        if (ModSable.isPlayerOnSubLevel(player)) {
            if (crossDim) {
                sendInsufficientAuthorityMessage(player);
                return false;
            }
            ensureChunkLoaded(targetLevel, destX, destZ);
            if (!ModSable.teleportSubLevel(player, destX, destY, destZ)) {
                return false;
            }
        } else {
            Entity vehicle = player.getVehicle();
            if (crossDim) {
                DimensionTransition transition = new DimensionTransition(
                    targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
                    player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
                if (vehicle != null) {
                    vehicle.changeDimension(transition);
                } else {
                    player.changeDimension(transition);
                }
            } else {
                ensureChunkLoaded(targetLevel, destX, destZ);
                if (vehicle != null) {
                    List<Entity> passengers = new ArrayList<>(vehicle.getPassengers());
                    for (Entity passenger : passengers) {
                        passenger.stopRiding();
                    }
                    vehicle.teleportTo(destX, destY, destZ);
                    DimensionTransition transition = new DimensionTransition(
                        targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
                        player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
                    player.changeDimension(transition);
                    for (Entity passenger : passengers) {
                        if (passenger != player && passenger instanceof ServerPlayer serverPassenger) {
                            serverPassenger.connection.teleport(destX, destY, destZ, serverPassenger.getYRot(), serverPassenger.getXRot());
                            serverPassenger.connection.resetPosition();
                        }
                    }
                    for (Entity passenger : passengers) {
                        passenger.startRiding(vehicle, true);
                    }
                } else {
                    DimensionTransition transition = new DimensionTransition(
                        targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
                        player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
                    player.changeDimension(transition);
                }
            }
        }
        player.resetFallDistance();

        targetLevel.sendParticles(ParticleTypes.PORTAL,
            destX, destY + 1, destZ,
            32, 0.5, 1.0, 0.5, 0.5);

        if (crossDim) {
            sourceLevel.sendParticles(ParticleTypes.PORTAL,
                player.getX(), player.getY() + 1, player.getZ(),
                32, 0.5, 1.0, 0.5, 0.5);
        }

        targetLevel.playSound(null, destX, destY, destZ,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.0f);

        player.hurt(player.damageSources().fall(), FALL_DAMAGE);

        consumePearl(player, pearlStack, unexplored);
        LivingEnderPearlFunction.setCooldown(player, COOLDOWN_TICKS);

        return true;
    }

    private static void consumePearl(ServerPlayer player, ItemStack pearlStack, boolean unexplored) {
        if (player.isCreative()) return;
        if (unexplored) {
            pearlStack.shrink(MapTeleportExecutor.UNEXPLORED_PEARL_COST);
        } else {
            pearlStack.shrink(1);
        }
    }

    private static void ensureChunkLoaded(ServerLevel level, double x, double z) {
        int chunkX = (int) x >> 4;
        int chunkZ = (int) z >> 4;
        level.getChunk(chunkX, chunkZ);
    }

    private static int findSafeY(ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        level.getChunk(chunkX, chunkZ);
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY();
    }

    public static void sendBannerTeleportMessage(ServerPlayer player, Component bannerName) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.teleported_to_banner", bannerName),
            true);
    }

    public static void sendTargetPointMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.teleported_to_target"),
            true);
    }

    public static void sendUnexploredMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.unexplored"),
            true);
    }

    public static void sendCrossDimensionMessage(ServerPlayer player, ResourceKey<Level> dimension) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.cross_dimension",
                Component.translatable("dimension." + dimension.location().getNamespace() + "." + dimension.location().getPath())),
            true);
    }

    public static void sendInsufficientAuthorityMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.insufficient_authority"),
            true);
    }
}