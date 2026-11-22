package com.qiqi.li.living.domain.battle;

import java.util.Optional;

import com.qiqi.li.living.domain.battle.entity.BridgeEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/**
 * 索敌 Goal —— 敌军仇恨选择的唯一维护点（prio 0，<b>无旗标</b>，常驻并跑）。
 *
 * <p>自由围猎口径：战区内最近棋子 → setTarget；无棋子 → setTarget(null)
 * （原版近战 Goal 随即停，怪原地待机）。</p>
 *
 * <p>⚠️ <b>脑怪双写</b>：1.16+ 脑驱动生物（猪灵/疣猪兽/监守者/大量模组怪）
 * 读 {@code ATTACK_TARGET} 记忆而非 {@code getTarget()} ⇒ 两种都要写；
 * 坚守者另有愤怒值入口（{@code increaseAngerAt}）。缺任意一种，
 * 对应类别的怪"被喂了目标也不动"。</p>
 *
 * <p>⚠️ <b>朝向重定向</b>：原版 body 旋转管线在"目标换到身后"时会长时间倒走
 * （LivingEntity :2508 移动与朝向夹角 &gt;95° 保持脸朝前的倒走分支 + :2515 攻击动画
 * 期间 body 锁 yRot + body 30%/tick 插值）——自由围猎频繁换目标 ⇒ 该分支高频触发。
 * 目标切换即一次性重定向朝向（索敌层的职责），消除倒走观感。</p>
 */
public class BridgeTargetingGoal extends Goal {

    private final PathfinderMob mob;
    private final BattleLaneAI.Params params;

    BridgeTargetingGoal(PathfinderMob mob) {
        this.mob = mob;
        this.params = BattleLaneAI.Params.read(mob);
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BridgeEntity bridge = BattleLaneAI.findNearestBridge(this.mob.level(),
            this.params.plotOrigin(), this.params.lanes(), this.params.depth(), this.mob);
        LivingEntity previous = this.mob.getTarget();
        this.mob.setTarget(bridge);
        // 脑怪双写（传统 setTarget 之外的另一半）
        this.mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, Optional.ofNullable(bridge));
        this.mob.getBrain().setMemory(MemoryModuleType.ANGRY_AT,
            Optional.ofNullable(bridge == null ? null : bridge.getUUID()));
        if (this.mob instanceof Warden warden && bridge != null) {
            warden.increaseAngerAt(bridge, AngerLevel.ANGRY.getMinimumAnger() + 20, false);
        }
        // 目标切换 ⇒ 立即面向新目标（消除原版倒走/动画锁的别扭观感）
        if (bridge != null && bridge != previous) {
            faceTowards(bridge.position());
        }
    }

    private void faceTowards(Vec3 target) {
        float yaw = (float) (Mth.atan2(target.z - this.mob.getZ(),
            target.x - this.mob.getX()) * (180F / (float) Math.PI)) - 90.0F;
        this.mob.setYRot(yaw);
        this.mob.yRotO = yaw;
        this.mob.yBodyRot = yaw;
        this.mob.yBodyRotO = yaw;
        this.mob.yHeadRot = yaw;
        this.mob.yHeadRotO = yaw;
    }
}
