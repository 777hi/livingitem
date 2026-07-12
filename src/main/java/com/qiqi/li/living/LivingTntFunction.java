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

    /**
     * 检查活TNT是否正在燃烧（引信倒计时中）。
     *
     * <p>从 LIVING_FUNCTION_DATA 组件中读取 explosion.ignited 状态。
     * 供客户端图标系统使用。
     *
     * @param stack 物品栈
     * @return 如果引信正在倒计时返回 true
     */
    public static boolean isFuseActive(ItemStack stack) {
        net.minecraft.nbt.CompoundTag tntTag = LivingItemManager.getFunctionData(stack, ID);
        if (tntTag.isEmpty()) return false;
        net.minecraft.nbt.CompoundTag explosionTag = tntTag.getCompound(ExplosionComponent.ID);
        return explosionTag.getBoolean("ignited");
    }

    /**
     * 获取活TNT的引信剩余时间。
     *
     * <p>从 LIVING_FUNCTION_DATA 组件中读取 explosion.fuse_timer。
     * 供客户端图标系统使用，用于判断闪烁动画帧。
     *
     * @param stack 物品栈
     * @return 引信剩余 tick 数；如果未点燃返回 -1
     */
    public static int getFuseTimer(ItemStack stack) {
        net.minecraft.nbt.CompoundTag tntTag = LivingItemManager.getFunctionData(stack, ID);
        if (tntTag.isEmpty()) return -1;
        net.minecraft.nbt.CompoundTag explosionTag = tntTag.getCompound(ExplosionComponent.ID);
        if (!explosionTag.getBoolean("ignited")) return -1;
        return explosionTag.getInt("fuse_timer");
    }
}