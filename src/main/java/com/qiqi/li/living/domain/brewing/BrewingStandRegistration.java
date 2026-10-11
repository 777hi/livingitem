package com.qiqi.li.living.domain.brewing;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活酿造台域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>⚠️ 新增活物品时改本类，<b>不要</b>往 {@code LivingItem#commonSetup} 里加。</p>
 */
public final class BrewingStandRegistration {

    private BrewingStandRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingBrewingStandFunction());
        // 运行时片段：批次倒计时 + 本批次材料（瞬态，不进组件 ⇒ 不影响堆叠）。
        // 与 FurnaceSegment 同构（档 2）。
        RuntimeSegmentRegistry.register(BrewingStandSegment.INSTANCE);
    }
}
