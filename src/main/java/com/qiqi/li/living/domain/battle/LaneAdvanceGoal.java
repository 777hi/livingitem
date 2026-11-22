package com.qiqi.li.living.domain.battle;

import java.util.EnumSet;

import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 推进 Goal —— 敌军仇恨选择与推进的唯一驱动点（§2.2）。
 *
 * <p>每 tick 常驻评估（桥被拖走 / 铲除要立刻换目标）：车道内最近桥 →
 * {@code setTarget}（原版 {@code MeleeAttackGoal} 免费接手寻路 / 逼近 / 啃咬动画）；
 * 无桥 → 向第 0 列锚点推进。车道参数在 spawn 时写进敌军 persistent NBT
 * （敌军不落盘 ⇒ Goal 不用考虑存档重载重建）。</p>
 */
public class LaneAdvanceGoal extends Goal {

    private static final String KEY_PLOT = "li_battle_plot";
    private static final String KEY_LANE = "li_battle_lane";
    private static final String KEY_DEPTH = "li_battle_depth";

    private final PathfinderMob mob;
    private final BlockPos plotOrigin;
    private final int lane;
    private final int depth;

    /** spawn 时写入车道参数；Goal 构造时读回。 */
    public static void writeLaneParams(PathfinderMob mob, BlockPos plotOrigin, int lane, int depth) {
        CompoundTag data = mob.getPersistentData();
        data.putLong(KEY_PLOT, plotOrigin.asLong());
        data.putInt(KEY_LANE, lane);
        data.putInt(KEY_DEPTH, depth);
    }

    public LaneAdvanceGoal(PathfinderMob mob) {
        this.mob = mob;
        CompoundTag data = mob.getPersistentData();
        this.plotOrigin = BlockPos.of(data.getLong(KEY_PLOT));
        this.lane = data.getInt(KEY_LANE);
        this.depth = Math.max(0, data.getInt(KEY_DEPTH));
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.depth > 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // 常驻重评估（寻路不看实体：桥每 tick 都可能被拖走 / 铲除 / 新建）
        AABB strip = ArenaPlots.laneStrip(this.plotOrigin, this.depth, this.lane);
        BridgeEntity target = null;
        double best = Double.MAX_VALUE;
        for (BridgeEntity bridge : this.mob.level().getEntitiesOfClass(BridgeEntity.class, strip)) {
            double dist = bridge.distanceToSqr(this.mob);
            if (dist < best) {
                best = dist;
                target = bridge;
            }
        }
        if (target != null && target.isAlive()) {
            this.mob.setTarget(target);
            return;
        }
        this.mob.setTarget(null);
        Vec3 anchor = ArenaPlots.columnAnchor(this.plotOrigin, this.lane);
        this.mob.getNavigation().moveTo(anchor.x, anchor.y, anchor.z, 1.0);
    }
}
