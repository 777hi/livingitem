package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送后地图更新跳过的节流器（服务端）。
 *
 * <p>传送完成后的若干 tick 内，跳过 {@code MapItem.update()} 的同步阻塞调用，避免客户端空白卡顿；
 * 同时对活地图限制单 tick 内的刷新频率，防止高频重复刷新。</p>
 */
public final class MapUpdateSkipHelper {

    private static final int SKIP_TICKS = 40;

    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final Map<UUID, Long> lastUpdateTick = new HashMap<>();

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

    public static boolean shouldSkipUpdate(ServerPlayer player, ItemStack stack) {
        if (shouldSkip(player)) return true;

        if (LivingItemManager.isLivingMap(stack)) {
            long currentTick = player.serverLevel().getServer().getTickCount();
            Long lastTick = lastUpdateTick.get(player.getUUID());
            if (lastTick != null && currentTick - lastTick < 20) {
                return true;
            }
            lastUpdateTick.put(player.getUUID(), currentTick);
        }

        return false;
    }
}