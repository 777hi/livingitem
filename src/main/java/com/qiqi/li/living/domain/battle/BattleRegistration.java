package com.qiqi.li.living.domain.battle;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 战局领域注册入口（挂点见 {@code LivingItem#commonSetup}）。
 *
 * <p>⚠️ 本类只放 {@code LivingItemManager.registerFunction}（check_layers R4）；
 * 实体注册总线在 {@code LivingItem} 构造函数，属性注册在 {@code BattleEntities}。</p>
 */
public final class BattleRegistration {

    private BattleRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingBattleFunction());
    }
}
