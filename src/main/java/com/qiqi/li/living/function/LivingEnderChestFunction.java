package com.qiqi.li.living.function;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.orchestrator.Orchestrators;

/**
 * 活末影箱功能 —— 无线传输路由器。
 *
 * <h3>功能概述</h3>
 * 活末影箱不存储任何物品，本质是一个路由器。它通过全局路由表
 * 将不同容器中的漏斗连接起来，实现跨容器无线传输：
 * <ul>
 *   <li><strong>Push（注册路由）</strong>：漏斗 output→活末影箱时，注册路由条目</li>
 *   <li><strong>Pull（无线提取）</strong>：漏斗 input→活末影箱时，查路由表跳转到源容器提取</li>
 * </ul>
 *
 * <h3>频道隔离</h3>
 * 堆叠数 = 频道号。堆叠数为 2 的活末影箱只能与堆叠数为 2 的活末影箱配对。
 *
 * <h3>组件配置</h3>
 * 活末影箱本身不需要 tick 组件，路由和传输由：
 * <ul>
 *   <li>{@link com.qiqi.li.living.core.accessor.LivingEnderChestAccessor} 处理</li>
 *   <li>{@link com.qiqi.li.living.core.accessor.EnderChannelRegistry} 维护路由表</li>
 *   <li>{@link com.qiqi.li.living.core.components.ItemTransferComponent} 触发</li>
 * </ul>
 */
public class LivingEnderChestFunction extends BaseLivingFunction {

    public static final String ID = "living_ender_chest";

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false)
        .withOrchestrator(Orchestrators.SIMPLE);

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.ender_chest.status"; }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    /**
     * 判断物品是否为活末影箱。
     *
     * @param stack 要检查的物品
     * @return true 如果是活末影箱
     */
    public static boolean isLivingEnderChest(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }
}