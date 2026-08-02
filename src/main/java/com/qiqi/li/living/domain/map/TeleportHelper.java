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
import net.minecraft.world.level.block.state.BlockState;
import com.qiqi.li.living.function.LivingEnderPearlFunction;

import java.util.Set;

public final class TeleportHelper {

    public static final int COOLDOWN_TICKS = 40;
    public static final float FALL_DAMAGE = 5.0f;

    private TeleportHelper() {}

    public static boolean teleportToMapPosition(ServerPlayer player, ServerLevel sourceLevel,
                                                 ServerLevel targetLevel,
                                                 double worldX, double worldZ,
                                                 ItemStack pearlStack) {
        if (LivingEnderPearlFunction.isOnCooldown(pearlStack)) return false;

        BlockPos targetPos = BlockPos.containing(worldX, player.getY(), worldZ);
        int safeY = findSafeY(targetLevel, targetPos);
        if (safeY < targetLevel.getMinBuildHeight()) return false;

        double destX = worldX + 0.5;
        double destY = safeY + 1.0;
        double destZ = worldZ + 0.5;

        boolean crossDim = player.level().dimension() != targetLevel.dimension();

        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            vehicle.dismountTo(destX, destY, destZ);
        }

        if (crossDim) {
            player.teleportTo(targetLevel, destX, destY, destZ, Set.of(), player.getYRot(), player.getXRot());
        } else {
            player.teleportTo(destX, destY, destZ);
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

        consumePearl(player, pearlStack);
        LivingEnderPearlFunction.setCooldown(pearlStack, COOLDOWN_TICKS);

        return true;
    }

    public static boolean teleportToBanner(ServerPlayer player, ServerLevel sourceLevel,
                                            ServerLevel targetLevel,
                                            BlockPos bannerPos,
                                            ItemStack pearlStack) {
        if (LivingEnderPearlFunction.isOnCooldown(pearlStack)) return false;

        int chunkX = bannerPos.getX() >> 4;
        int chunkZ = bannerPos.getZ() >> 4;
        targetLevel.getChunk(chunkX, chunkZ);

        double destX = bannerPos.getX() + 0.5;
        double destY = bannerPos.getY() + 1.0;
        double destZ = bannerPos.getZ() + 0.5;

        boolean crossDim = player.level().dimension() != targetLevel.dimension();

        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            vehicle.dismountTo(destX, destY, destZ);
        }

        if (crossDim) {
            player.teleportTo(targetLevel, destX, destY, destZ, Set.of(), player.getYRot(), player.getXRot());
        } else {
            player.teleportTo(destX, destY, destZ);
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

        consumePearl(player, pearlStack);
        LivingEnderPearlFunction.setCooldown(pearlStack, COOLDOWN_TICKS);

        return true;
    }

    private static void consumePearl(ServerPlayer player, ItemStack pearlStack) {
        if (player.isCreative()) return;
        pearlStack.shrink(1);
    }

    private static int findSafeY(ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        level.getChunk(chunkX, chunkZ);
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos).getY();
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

    public static void sendMapCenterMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("chat.livingitem.ender_pearl.teleported_to_center"),
            true);
    }
}