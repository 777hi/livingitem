package com.qiqi.li.living.domain.battle;

import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 战场敌军 AI 的共享件：场地参数（persistent NBT）+ 全场最近棋子扫描。
 *
 * <p>自由围猎口径（D-battle-05）：怪不锁车道，索敌范围 = 整个战区 AABB，
 * 目标 = 最近的可攻击棋子（桥实体）。敌军不落盘 ⇒ NBT 参数无重载问题。</p>
 */
final class BattleLaneAI {

    private static final String KEY_PLOT = "li_battle_plot";
    private static final String KEY_LANES = "li_battle_lanes";
    private static final String KEY_DEPTH = "li_battle_depth";

    private BattleLaneAI() {}

    static void writeParams(PathfinderMob mob, BlockPos plotOrigin, int lanes, int depth) {
        CompoundTag data = mob.getPersistentData();
        data.putLong(KEY_PLOT, plotOrigin.asLong());
        data.putInt(KEY_LANES, lanes);
        data.putInt(KEY_DEPTH, depth);
    }

    /** 场地参数（spawn 时写入敌军 persistent NBT）。 */
    record Params(BlockPos plotOrigin, int lanes, int depth) {
        static Params read(PathfinderMob mob) {
            CompoundTag data = mob.getPersistentData();
            return new Params(BlockPos.of(data.getLong(KEY_PLOT)),
                Math.max(1, data.getInt(KEY_LANES)), Math.max(1, data.getInt(KEY_DEPTH)));
        }
    }

    /** 战区内最近的可攻击棋子（按参照点距离，全场不限行）；无则 null。 */
    static BridgeEntity findNearestBridge(Level level, BlockPos plotOrigin, int lanes, int depth,
                                          Entity reference) {
        AABB bounds = ArenaPlots.plotBounds(plotOrigin, lanes, depth);
        BridgeEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (BridgeEntity bridge : level.getEntitiesOfClass(BridgeEntity.class, bounds)) {
            double dist = bridge.distanceToSqr(reference);
            if (dist < bestDist) {
                bestDist = dist;
                best = bridge;
            }
        }
        return best != null && best.isAlive() ? best : null;
    }
}
