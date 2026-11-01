package com.qiqi.li.living.domain.map;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.util.StaticCacheRegistry;
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
 *
 * <h3>⚠️ 两张表记的是「服务端 tick 计数」，必须随停服清空</h3>
 * <p>{@code getTickCount()} 从 0 起算，而单机「退出存档 → 进另一个存档」<b>不重启 JVM</b>
 * （static 跨存档存活）⇒ 旧存档留下的 {@code lastUpdateTick = 500000} 会与新存档的
 * {@code currentTick = 20} 相比，{@code currentTick - lastTick < 20} 恒真 ⇒
 * <b>新存档里活地图的刷新被永久跳过</b>（图标 / 装饰不再更新，且没有任何报错）。
 * {@code cooldowns} 同形：{@code currentTick < expireTick} 对旧值恒真。</p>
 */
public final class MapUpdateSkipHelper {

    private static final int SKIP_TICKS = 40;

    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final Map<UUID, Long> lastUpdateTick = new HashMap<>();

    static {
        // 与 clear() 同文件登记（StaticCacheRegistry 类注释的「登记位置」约定）
        StaticCacheRegistry.onServerStop(s -> clear());
    }

    private MapUpdateSkipHelper() {}

    /** 停服 / 切存档时清空 —— 判据见类头的 tick 计数说明。 */
    public static void clear() {
        cooldowns.clear();
        lastUpdateTick.clear();
    }

    /**
     * 玩家下线时丢掉他自己的两条记录。
     *
     * <p>两表按玩家 UUID 增长且没有任何自然过期路径覆盖「再也不上线的人」
     * （{@code shouldSkip} 只在被查询时删除过期项）。挂在活地图已有的下线处理器里，
     * 不新开事件。</p>
     */
    public static void onPlayerLoggedOut(UUID playerUuid) {
        cooldowns.remove(playerUuid);
        lastUpdateTick.remove(playerUuid);
    }

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