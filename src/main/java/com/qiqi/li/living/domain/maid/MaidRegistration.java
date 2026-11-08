package com.qiqi.li.living.domain.maid;

import com.qiqi.li.living.api.LivingItemManager;

/** 活魂符领域登记入口。 */
public final class MaidRegistration {
    private MaidRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingSoulSlabFunction());
    }
}
