package com.qiqi.li.living.domain.tools;

import java.util.Set;

import com.qiqi.li.LivingItem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * 活武器<b>辅助攻击</b> —— 玩家打中怪时，背包里【无记忆】的活武器**一起出手**
 * （{@code docs/idea.md} §1.7）。
 *
 * <h3>为什么必须干预原版的「无敌间隔」</h3>
 *
 * <p>原版规则：生物受击后 {@code invulnerableTime = 20}，且只要 {@code > 10} 就
 * <b>完全免疫</b> ⇒ **同一只怪在一个无敌窗口内只能吃一次伤害**。
 * 而玩家自己的攻击<b>已经占掉了这个窗口</b> ⇒ 活武器随后打的全部被吞
 * （伤害、附魔、击退<b>都不触发</b>）。</p>
 *
 * <p>⇒ 故用 NeoForge 官方接口把辅助攻击期间的无敌压到 0：</p>
 * <pre>
 *   LivingIncomingDamageEvent → container.setPostAttackInvulnerabilityTicks(0)
 * </pre>
 *
 * <p>⭐ <b>这是官方入口</b>（铁魔法 {@code DamageSources#preHitEffects} 同款），
 * 不是改 {@code invulnerableTime} 字段 —— 见 {@code docs/tech/living-tool-tech.md} §13。</p>
 *
 * <h3>⚠️ 为什么只影响辅助攻击</h3>
 *
 * <p>{@code active} 标志只在辅助攻击循环期间为 true ⇒</p>
 * <ul>
 *   <li>玩家自己的那一刀<b>不受影响</b>（仍有无敌限制）</li>
 *   <li>自主模式（有记忆、每 tick 打）<b>不受影响</b> —— 否则 DPS 会失控</li>
 * </ul>
 */
@EventBusSubscriber(modid = LivingItem.MOD_ID)
public final class LivingWeaponAssist {

    /**
     * 辅助攻击进行中的标志。
     *
     * <p>⚠️ 事件由 {@code hurt()} <b>同步</b> post ⇒ 简单的静态布尔即可（服务端单线程）。</p>
     */
    private static boolean active = false;

    private LivingWeaponAssist() {
    }

    /** 是否正处于辅助攻击期间（供压制无敌帧 / 取消击退使用）。 */
    public static boolean isActive() {
        return active;
    }

    /**
     * 玩家攻击命中生物 ⇒ 背包里无记忆的活武器一起出手。
     *
     * <p>⭐ <b>挂在伤害结算之后</b>（{@code Post}）：让玩家那一刀先正常生效，
     * 活武器随后补刀（靠压制无敌帧让每一刀都生效）。
     * 若改挂 {@code AttackEntityEvent}（攻击前）⇒ 活武器先打 ⇒
     * <b>玩家自己的伤害反被无敌吞掉</b>。</p>
     *
     * <p>⚠️ <b>必须排除 FakePlayer</b>：活武器自己造成的伤害也会触发本事件 ⇒ 不排除会无限递归。</p>
     */
    @SubscribeEvent
    public static void onPlayerDamage(LivingDamageEvent.Post event) {
        if (active) {
            return;   // 已在辅助攻击中（理论上不该发生，保险）
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isFakePlayer()) {
            return;
        }
        // ⭐ 创造模式 2026-09-25 放开（用户定，与辅助挖掘对齐）：创造打怪时活武器照样
        //    出手有实际意义，且创造物品不掉耐久、无副作用；旁观依旧排除。
        if (player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target.isDeadOrDying()) {
            return;
        }

        Inventory inventory = player.getInventory();
        ServerLevel level = player.serverLevel();
        Vec3 origin = player.getEyePosition();
        long now = level.getGameTime();

        active = true;
        boolean any = false;
        try {
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (slot == inventory.selected) {
                    continue;   // 主手已由玩家自己挥出（同辅助挖掘的口径）
                }
                // ⭐ 怪中途死了就停 —— 否则后面的刀【不造成伤害、不触发附魔，却仍扣耐久】
                if (target.isDeadOrDying()) {
                    break;
                }
                ItemStack weapon = inventory.getItem(slot);
                if (!LivingToolRecorder.isAssistWeapon(weapon)) {
                    continue;
                }

                // 临时构造一条「宿主 → 目标」的射线，复用回放（无记忆 ⇒ 现算）
                LivingToolMemory.AttackMemory temp = LivingToolMemory.AttackMemory.record(
                    origin, target.getBoundingBox().getCenter(), null);

                // ⭐ 压制无敌帧，让【这一把】的伤害与附魔真正生效
                //    （顺序：hurt 内 1153 post 事件 → 1190 免疫判断 → 1202 读 container）
                //
                //    官方入口见 onIncomingDamage（setPostAttackInvulnerabilityTicks）；
                //    但 1190 的免疫判断读的是【当前 invulnerableTime】，而官方入口只能改【本次之后】的值
                //    ⇒ 本循环里再直接压一道，确保下一把不会被吞。
                //    invulnerableTime 是 public 字段（Entity.java），无需反射 / AT。
                if (target.invulnerableTime > 10) {
                    target.invulnerableTime = 10;   // 进 hurt 时会先 -1 ⇒ 9 ⇒ 跨过 `> 10` 的免疫阈值
                }

                LivingToolReplay.AttackResult result = LivingToolReplay.replayAttack(
                    weapon, temp, origin, Set.of(), level, now);

                if (result.outcome() == LivingToolReplay.Outcome.ATTACKED) {
                    inventory.setItem(slot, result.tool());   // 写回（可能扣了耐久 / 损坏）
                    any = true;
                }
            }
        } finally {
            active = false;
        }

        if (any) {
            player.inventoryMenu.broadcastChanges();
        }
    }

    /**
     * 仅辅助攻击期间：把无敌间隔压到 0 ⇒ **每一把的伤害与附魔都生效**。
     *
     * <p>⭐ 官方入口，与铁魔法 {@code DamageSources#preHitEffects} 同款。</p>
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!active) {
            return;
        }
        event.getContainer().setPostAttackInvulnerabilityTicks(0);
    }

    /**
     * 仅辅助攻击期间：取消击退。
     *
     * <p>⚠️ 否则 N 把武器各推一次 ⇒ <b>怪会被崩飞</b>，不像"围殴"。
     * （铁魔法用 {@code LivingKnockBackEvent} 做同样的事。）</p>
     */
    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent event) {
        if (!active) {
            return;
        }
        event.setCanceled(true);
    }
}
