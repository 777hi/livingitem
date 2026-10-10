package com.qiqi.li.living.domain.brewing;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活酿造台域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>⚠️ 新增活物品时改本类，<b>不要</b>往 {@code LivingItem#commonSetup} 里加。</p>
 */
public final class BrewingStandRegistration {

    private BrewingStandRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingBrewingStandFunction());
        // 第二段再加入运行时片段（BrewingStandSegment）：承载「酿造进度 / 燃料次数」的
        // 展示数据（tooltip 用），与 FurnaceSegment 同构。第一段无瞬态数据可展示，故不注册。
    }
}
