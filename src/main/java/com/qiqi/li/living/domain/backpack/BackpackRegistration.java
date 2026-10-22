package com.qiqi.li.living.domain.backpack;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活背包注册入口（A1：注册按域下放，见 {@code RedstoneRegistration} 的类注释）。
 *
 * <p>⚠️ 只注册功能本身；「哪些物品算物品形态容器」由<b>联动模块</b>通过
 * {@code ItemFormContainers.register} 提供（核心不认识第三方类型，`D-container-09`）。</p>
 */
public final class BackpackRegistration {

    private BackpackRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingBackpackFunction());
    }
}
