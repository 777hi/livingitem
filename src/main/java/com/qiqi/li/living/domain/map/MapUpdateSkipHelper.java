package com.qiqi.li.living.domain.map;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MapUpdateSkipHelper {

    private static final int SKIP_TICKS = 40;

    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private MapUpdateSkipHelper() {}

    public static void markTeleported(ServerPlayer player) {
        long currentTick = player.serverLevel().getServer().getTickCount();
        cooldowns.put(player.getUUID(), currentTick + SKIP_TICKS);
    }

    public static boolean shouldSkip(ServerPlayer player) {
        Long expireTick = cooldowns.get(player.getUUID());
        if (expireTick == null) return false;
        long currentTick = player.serverLevel().getServer().getTickCount();
        if (currentTick < expireTick) return true;
        cooldowns.remove(player.getUUID());
        return false;
    }
}