package com.qiqi.li.living.domain.battle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 战场残留扫描（低频）：容器被破坏时战局 tick 停，场地残留（平台+敌军）与 ticket
 * 无人清理 ⇒ 主世界每 100 tick 扫一遍活跃 venue，容器已不在的清场出册
 * （进度随瓶掉落，不受影响）。
 */
@EventBusSubscriber
public final class BattleVenueSweeper {

    private static final int SWEEP_PERIOD_TICKS = 100;

    private BattleVenueSweeper() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % SWEEP_PERIOD_TICKS != 0) return;
        BattleVenues.sweep(level.getServer());
    }
}
