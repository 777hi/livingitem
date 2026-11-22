package com.qiqi.li.living.domain.battle.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * 战场敌军（spike：僵尸）—— 原版 AI 属性直读，只改两件事：
 *
 * <ul>
 *   <li><b>不落盘</b>：区块卸载 = 消失（wave 重召），不残留存档 —— 非持久同时让
 *       spawn 时注入的战场 AI 皮永不因存档重载丢失（registerGoals 不重跑）；</li>
 *   <li><b>不被远处 despawn</b>：竞技场无玩家常驻时，原版 {@code removeWhenFarAway}
 *       会按 128 格规则清掉敌军 —— spike 的「玩家在场才跑」模型下敌军只随区块卸载消失。</li>
 * </ul>
 */
public class BattleZombieEntity extends Zombie {

    public BattleZombieEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }
}
