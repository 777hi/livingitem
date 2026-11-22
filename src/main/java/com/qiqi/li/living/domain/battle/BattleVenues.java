package com.qiqi.li.living.domain.battle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.qiqi.li.living.container.ContainerKeys;
import com.qiqi.li.living.domain.battle.entity.BattleZombieEntity;
import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import com.qiqi.li.living.util.StaticCacheRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 活跃战场登记（battleKey → venue）——容器破坏残留兜底。
 *
 * <p>战局 tick 随容器存活而跑；**容器被破坏时战局 tick 停**，场地残留（平台方块 +
 * 敌军）与 ticket 无人清理 ⇒ `sweep`（低频服务端扫描）检查容器存活，已不在的
 * venue 清场（平台+实体+ticket）。进度随瓶掉落，不受影响。</p>
 *
 * <p>⚠️ ticket 持久化在 ForcedChunksSavedData，重启自动重挂 ⇒ 重启残留由
 * {@code BattleTickets.validate} 清（容器已不在的），运行态自愈交回战局 tick。</p>
 */
public final class BattleVenues {

    /** 活跃战场：battleKey → venue。 */
    private static final Map<String, Venue> ACTIVE = new HashMap<>();

    record Venue(net.minecraft.resources.ResourceKey<Level> dim, BlockPos plotPos,
                 BlockPos ownerPos, int lanes, int depth) {}

    static {
        StaticCacheRegistry.onServerStop(server -> ACTIVE.clear());
    }

    private BattleVenues() {}

    public static void register(String battleKey, ServerLevel level, BlockPos plotPos,
                                BlockPos ownerPos, int lanes, int depth) {
        ACTIVE.put(battleKey,
            new Venue(level.dimension(), plotPos, ownerPos, lanes, depth));
    }

    /** 清场：战区实体 discard + 平台方块清除 + ticket 释放。 */
    public static void clear(MinecraftServer server, String battleKey) {
        Venue venue = ACTIVE.remove(battleKey);
        if (venue == null) return;
        ServerLevel level = server.getLevel(venue.dim());
        if (level == null) return;
        var bounds = ArenaPlots.plotBounds(venue.plotPos(), venue.lanes(), venue.depth());
        for (Entity entity : level.getEntities().getAll()) {
            if (bounds.intersects(entity.getBoundingBox())
                && (entity instanceof BridgeEntity || entity instanceof BattleZombieEntity
                    || entity.getTags().contains(BattleEnemies.TAG_ENEMY))) {
                entity.discard();
            }
        }
        ArenaPlots.clearPlatform(level, venue.plotPos(), venue.lanes(), venue.depth());
        BattleTickets.setArena(level, venue.ownerPos(), venue.plotPos(), venue.lanes(), venue.depth(), false);
    }

    /** 残留扫描（低频调用）：容器已不在的 venue 清场并出册。⚠️ 先收集后删除（迭代中改表 = CME）。 */
    public static void sweep(MinecraftServer server) {
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Venue> entry : ACTIVE.entrySet()) {
            Venue venue = entry.getValue();
            ServerLevel level = server.getLevel(venue.dim());
            if (level == null) continue;
            boolean alive = false;
            for (BlockPos pos : ContainerKeys.positionsOf(entry.getKey())) {
                if (level.getBlockEntity(pos) != null) {
                    alive = true;
                    break;
                }
            }
            if (!alive) dead.add(entry.getKey());
        }
        for (String key : dead) {
            clear(server, key);   // 内部做 ACTIVE.remove（迭代已结束）
        }
    }
}
