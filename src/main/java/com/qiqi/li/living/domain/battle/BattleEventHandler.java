package com.qiqi.li.living.domain.battle;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 战场伤害路由（回向通道入口）：啃咬 → 取消原版伤害（桥本体不掉血，账在容器）
 * → 伤害入账本（战局 tick 统一结算掉层）→ 向事件流喊 {@code SWING}。
 *
 * <p>事件监听不在 {@code processIsolated} 隔离内：自 try/catch + 静默失败，
 * 绝不影响原版伤害流程（软逻辑口径）。</p>
 */
@EventBusSubscriber
public final class BattleEventHandler {

    private BattleEventHandler() {}

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        try {
            if (event.getEntity() instanceof com.qiqi.li.living.domain.battle.entity.BridgeEntity bridge) {
                event.setCanceled(true);
                if (!bridge.level().isClientSide && bridge.battleKey() != null) {
                    BattleDamageLedger.offer(bridge.battleKey(), bridge.slot(), event.getAmount());
                    BattleEventFeed.offer(bridge.battleKey(),
                        new BattleEvent(BattleEvent.Type.SWING, bridge.slot()));
                }
            }
        } catch (RuntimeException ignored) {
            // 事件钩子失败静默：只损失演出，不碰原版流程
        }
    }
}
