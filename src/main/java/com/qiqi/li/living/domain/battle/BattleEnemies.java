package com.qiqi.li.living.domain.battle;

import java.util.List;

import com.qiqi.li.living.domain.battle.entity.BattleZombieEntity;
import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 敌军波次与清场（spike：命令手动召唤）。
 *
 * <p>战场 AI 皮 = 清空 goalSelector/targetSelector 后注入推进 Goal + 近战 Goal
 * （模组生物同口径，§3）。敌军戴铁头盔挡白天燃烧（官方豁免链：有头盔扣耐久不点燃），
 * 且 {@code setDropChance(0)} 不留垃圾掉落。</p>
 */
public final class BattleEnemies {

    /** 战局成员标记（清场 / 事件圈定的参与者判据之一）。 */
    public static final String TAG_ENEMY = "living_item_battle_enemy";

    private BattleEnemies() {}

    /** 每 k 只敌军，沿各行东端（出怪边）轮转生成；返回实际生成数。 */
    public static int spawnWave(ServerLevel arena, BattleArenaData data, int perLane) {
        int spawned = 0;
        for (int row = 0; row < data.lanes(); row++) {
            for (int k = 0; k < perLane; k++) {
                BattleZombieEntity zombie = BattleEntities.ZOMBIE.get().create(arena);
                if (zombie == null) continue;
                Vec3 cell = ArenaPlots.cellCenter(data.plotPos(), row, data.depth() - 1);
                // 出怪边（东端），朝西
                zombie.moveTo(cell.x + (k % 2), cell.y, cell.z, 90.0F, 0.0F);
                zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
                zombie.addTag(TAG_ENEMY);
                BattleLaneAI.writeParams(zombie, data.plotPos(), data.lanes(), data.depth());
                zombie.goalSelector.getAvailableGoals().clear();
                zombie.targetSelector.getAvailableGoals().clear();
                // 两层：无旗标索敌（常驻，全场最近棋子）→ 原版近战（自由逼近，无行军 Goal）
                zombie.goalSelector.addGoal(0, new BridgeTargetingGoal(zombie));
                zombie.goalSelector.addGoal(1, new MeleeAttackGoal(zombie, 1.0, true));
                arena.addFreshEntity(zombie);
                data.enemies().add(zombie.getUUID());
                spawned++;
            }
        }
        return spawned;
    }

    /** 只清敌军（战败重置用）：桥与战局保留。 */
    public static int clearEnemies(ServerLevel arena, BattleArenaData data) {
        int removed = 0;
        var bounds = ArenaPlots.plotBounds(data.plotPos(), data.lanes(), data.depth());
        List<Entity> doomed = new java.util.ArrayList<>();
        for (Entity entity : arena.getEntities().getAll()) {
            if (!bounds.intersects(entity.getBoundingBox())) continue;
            if (entity instanceof BattleZombieEntity || entity.getTags().contains(TAG_ENEMY)) {
                doomed.add(entity);
            }
        }
        for (Entity entity : doomed) {
            entity.discard();
            removed++;
        }
        data.enemies().clear();
        return removed;
    }

    /** 清场：战区 AABB 内的桥（按名册 + 兜底）与 tag 敌军全部 discard；返回清理数。 */
    public static int clear(ServerLevel arena, BattleArenaData data) {
        int removed = clearEnemies(arena, data);
        var bounds = ArenaPlots.plotBounds(data.plotPos(), data.lanes(), data.depth());
        List<Entity> doomed = new java.util.ArrayList<>();
        for (Entity entity : arena.getEntities().getAll()) {
            if (!bounds.intersects(entity.getBoundingBox())) continue;
            if (entity instanceof BridgeEntity) {
                doomed.add(entity);
            }
        }
        for (Entity entity : doomed) {
            entity.discard();
            removed++;
        }
        data.bridgeBySlot().clear();
        return removed;
    }
}
