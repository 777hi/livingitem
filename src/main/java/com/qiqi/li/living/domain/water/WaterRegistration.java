package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.level.material.Fluids;

/**
 * 活水域注册入口（活水桶 / 活水车）—— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class WaterRegistration {

    private WaterRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingWaterBucketFunction());
        LivingItemManager.registerFunction(new LivingWaterWheelFunction());

        // 流体流动行为（1b-2 契约）：水 = 会流动，level 上限 7，流速预留 0
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(),
            FluidFlowBehavior.flowing(ContainerFluidData.MAX_FLOW_LEVEL, 0));
    }
}
