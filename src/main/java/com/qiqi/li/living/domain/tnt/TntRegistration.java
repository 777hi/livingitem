package com.qiqi.li.living.domain.tnt;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.function.LivingFlintAndSteelFunction;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 活TNT 域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p><b>跨领域规则的归属</b>：点燃是「活TNT × 活打火石」两个物品的合作，
 * 归属选 TNT 域 —— TNT 是被作用的一方、也是本规则的意义所在；
 * 打火石只是纯触发器（无 tick 逻辑，见 {@link LivingFlintAndSteelFunction}）。
 */
public final class TntRegistration {

    private TntRegistration() {}

    public static void register() {
        // ── 功能 ──
        LivingItemManager.registerFunction(new LivingTntFunction());
        LivingItemManager.registerFunction(new LivingFlintAndSteelFunction());

        // ── 交互：活打火石右键活TNT点燃（及反向）──
        // 规则（谁触发谁）在 interaction_rules.json（D2 全迁）；这里只注册行为（handler）。
        InteractionRegistry.registerHandler("ignite", new IgniteHandler());
        InteractionRegistry.registerHandler("ignite_carried", new IgniteCarriedHandler());

        // ── static 缓存清理（登记点归属见 StaticCacheRegistry 类注释）──
        // 待炸账本的**内存调度表**要清（条目本身随存档走，不需要清）
        StaticCacheRegistry.onServerStop(ExplosionLedger::clearAllRuntimeState);
    }
}
