package com.qiqi.li.living;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.ExplosionComponent;
import com.qiqi.li.living.core.interaction.InteractionEntry;

/**
 * 活 TNT 功能 —— 容器中的可爆炸活物品（含引信倒计时）。
 *
 * 功能概述：
 * 活 TNT 是一种被动型活物品，放置在容器中等待被点燃。
 * 在容器 GUI 中，光标持有活打火石右键活 TNT 即可点燃，
 * 引信倒计时 80 tick（4 秒）后爆炸，与原版 TNT 一致。
 * 爆炸威力由容器中所有活 TNT 的总数决定。
 *
 * 引信机制：
 *   - 活打火石右键活 TNT → 启动引信倒计时（80 tick）
 *   - 引信期间 tooltip 显示剩余时间
 *   - 引信归零后触发爆炸
 *   - 爆炸消耗容器中所有活 TNT（包括未点燃的）
 *
 * 爆炸威力：
 *   radius = 4.0 × sqrt(活 TNT 总数)
 *   - 1 个活 TNT → 半径 4.0（等同原版 TNT）
 *   - 64 个活 TNT → 半径 32.0
 *
 * 触发方式：
 *   - 容器 GUI 中活打火石右键活 TNT（通过 GuiInteractionPacket 网络包触发）
 *   - 后续可扩展：活红石信号触发
 *
 * 组件配置：
 *   - ExplosionComponent：管理引信状态和爆炸逻辑
 *
 * 交互规则：
 *   1. 光标持有活打火石 → 右键槽位中的活TNT → 点燃槽位TNT
 *      目标：活TNT，触发器：活打火石，动作ID："ignite" → IgniteHandler
 *
 *   2. 光标持有活TNT → 右键槽位中的活打火石 → 点燃光标TNT
 *      目标：活打火石，触发器：活TNT，动作ID："ignite_carried" → IgniteCarriedHandler
 */
public class LivingTntFunction extends BaseLivingFunction {

    public static final String ID = "living_tnt";

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false)
        .addComponent(ExplosionComponent.class,
            com.qiqi.li.living.core.ComponentConfig.empty())
        .addInteraction(new InteractionEntry(
            Items.TNT,
            Items.FLINT_AND_STEEL,
            1,
            "ignite"
        ))
        .addInteraction(new InteractionEntry(
            Items.FLINT_AND_STEEL,
            Items.TNT,
            1,
            "ignite_carried"
        ));

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.tnt.status"; }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.TNT) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    public static LivingFunctionConfig getStaticConfig() { return CONFIG; }
}