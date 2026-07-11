package com.qiqi.li.living;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.LivingFunctionConfig;

/**
 * 活打火石功能 —— 右键点燃容器中的活 TNT。
 *
 * 功能概述：
 * 活打火石是一种交互型活物品，用于点燃容器中的活 TNT。
 * 当玩家手持活打火石右键容器方块时，发送网络包到服务端，
 * 服务端检查容器中是否有活 TNT，如果有则触发爆炸。
 *
 * 交互流程：
 * 1. 玩家在容器GUI中，光标持有活打火石右键活TNT
 * 2. 客户端通过 GuiInteractionHelper 检测交互，发送 GuiInteractionPacket 到服务端
 * 3. 服务端通过 IgniteHandler 处理点燃逻辑
 * 4. 调用 ExplosionComponent.startFuseOnStack() 启动引信倒计时
 * 5. 引信归零后爆炸，消耗容器中所有活 TNT，并破坏容器方块
 *
 * 组件配置：
 * 无额外组件（活打火石本身不需要 tick 逻辑，仅作为交互触发器）
 *
 * 注意：
 * 活打火石的 tick() 为空操作。它的核心逻辑在
 * {@link com.qiqi.li.living.core.interaction.IgniteHandler} 中，
 * 由 GuiInteractionPacket 处理时直接调用 ExplosionComponent.startFuseOnStack()。
 */
public class LivingFlintAndSteelFunction extends BaseLivingFunction {

    public static final String ID = "living_flint_and_steel";

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false);

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.flint_and_steel.status"; }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    public static LivingFunctionConfig getStaticConfig() { return CONFIG; }
}