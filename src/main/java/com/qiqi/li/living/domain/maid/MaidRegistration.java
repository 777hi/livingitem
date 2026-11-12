package com.qiqi.li.living.domain.maid;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.InteractionPredicates;
import com.qiqi.li.living.interaction.InteractionRegistry;

/** 活魂符领域登记入口。 */
public final class MaidRegistration {
    private MaidRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingSoulSlabFunction());
        // 空光标右键坐下：规则 JSON 的 target 在 TLM 缺席时解析失败自动跳过
        InteractionRegistry.registerHandler("soul_slab_sit", new SoulSlabSitHandler());
        InteractionPredicates.register("soul_slab_sit_empty_cursor", (trigger, target) -> trigger.isEmpty());
    }
}
