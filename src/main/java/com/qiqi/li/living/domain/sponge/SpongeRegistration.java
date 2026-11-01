package com.qiqi.li.living.domain.sponge;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活海绵域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class SpongeRegistration {

    private SpongeRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingSpongeFunction());
        // 第二段：吸收（四邻直吸 + 逐源原子）。
        // 第三段：SpongeItemFluidHandler 能力注册（registerItem 与实现同拍，§5）+ GUI 汲
        //         + ContainerFluidHandler 退役执行。
    }
}
