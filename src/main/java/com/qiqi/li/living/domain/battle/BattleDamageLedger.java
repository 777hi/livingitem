package com.qiqi.li.living.domain.battle;

import java.util.HashMap;
import java.util.Map;

import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 伤害账本（按 battleKey 聚合）—— 伤害钩子在战局 tick 之外喊话的边车。
 *
 * <p>同拍多源伤害按槽位累加，战局 tick 统一 drain 结算（一次组件提交/一次数量原语，
 * 不逐击写槽）。fire-and-forget：drain 即清，无存储。</p>
 */
public final class BattleDamageLedger {

    private static final Map<String, Map<Integer, Float>> PENDING = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(server -> PENDING.clear());
    }

    private BattleDamageLedger() {}

    public static void offer(String battleKey, int slot, float amount) {
        if (battleKey == null || amount <= 0) return;
        PENDING.computeIfAbsent(battleKey, k -> new HashMap<>())
            .merge(slot, amount, Float::sum);
    }

    /** 取走该战局全部待结算伤害（槽位 → 累计伤害）。 */
    public static Map<Integer, Float> drain(String battleKey) {
        if (battleKey == null) return Map.of();
        Map<Integer, Float> pending = PENDING.remove(battleKey);
        return pending == null ? Map.of() : pending;
    }
}
