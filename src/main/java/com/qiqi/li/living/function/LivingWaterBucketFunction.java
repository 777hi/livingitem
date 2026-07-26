package com.qiqi.li.living.function;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.WaterSpreadComponent;

/**
 * 活水桶功能 —— 物品栏中的水源，向四周蔓延并推开非活物品。
 *
 * 功能概述：
 * 活水桶放入容器后，以自身槽位为水源，每 tick 向四周蔓延。
 * 空槽位被水流覆盖，非活物品被推开，活物品阻挡水流。
 * 移除活水桶后水流自动消失。
 *
 * 组件配置：
 * - WaterSpreadComponent：BFS 水流蔓延 + 推物品
 */
public class LivingWaterBucketFunction extends BaseLivingFunction {

    public static final String ID = "living_water_bucket";

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false)
        .addComponent(new WaterSpreadComponent());

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.water_bucket.status"; }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    public static LivingFunctionConfig getStaticConfig() { return CONFIG; }

    public static boolean isLivingWaterBucket(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(stack);
    }
}