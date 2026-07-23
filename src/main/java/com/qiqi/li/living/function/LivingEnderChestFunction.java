package com.qiqi.li.living.function;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.EnderChannelComponent;
import com.qiqi.li.living.core.orchestrator.Orchestrators;
import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

/**
 * 活末影箱功能 —— 无线传输路由器 / 玩家末影箱直连接口。
 *
 * <h3>功能概述</h3>
 * 活末影箱支持两种工作模式：
 * <ul>
 *   <li><strong>路由模式</strong>（无绑定玩家）：通过全局路由表将不同容器中的漏斗连接起来，实现跨容器无线传输</li>
 *   <li><strong>直连模式</strong>（有绑定玩家）：直接读写绑定玩家的末影箱背包，活漏斗与之交互时跳过路由表</li>
 * </ul>
 *
 * <h3>绑定机制</h3>
 * <ul>
 *   <li>玩家在末影箱 GUI 中，光标持有末影箱物品，点击活按钮 → 末影箱被活化并绑定当前玩家</li>
 *   <li>取消活化时清空绑定数据</li>
 *   <li>绑定后无论活末影箱放在哪个容器中，活漏斗都能直连该玩家的末影箱背包</li>
 * </ul>
 *
 * <h3>频道隔离</h3>
 * 路由模式下：堆叠数 = 频道号。直连模式下：频道号无意义。
 *
 * <h3>组件配置</h3>
 * 活末影箱通过 tick() 清理已移走的末影箱和源物品关联的路由。
 * 路由和传输由：
 * <ul>
 *   <li>{@link com.qiqi.li.living.core.accessor.LivingEnderChestAccessor} 处理</li>
 *   <li>{@link com.qiqi.li.living.core.accessor.EnderChannelRegistry} 维护路由表</li>
 *   <li>{@link com.qiqi.li.living.core.components.ItemTransferComponent} 触发</li>
 * </ul>
 */
public class LivingEnderChestFunction extends BaseLivingFunction {

    public static final String ID = "living_ender_chest";
    private static final String KEY_BOUND_UUID = "bound_player_uuid";
    private static final String KEY_BOUND_NAME = "bound_player_name";

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false)
        .withOrchestrator(Orchestrators.SIMPLE)
        .addComponent(EnderChannelComponent.class, ComponentConfig.empty());

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

    public static boolean isLivingEnderChest(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean hasBoundPlayer(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return false;
        CompoundTag data = LivingItemManager.getFunctionData(stack, ID);
        return data.contains(KEY_BOUND_UUID);
    }

    public static UUID getBoundPlayerUuid(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        CompoundTag data = LivingItemManager.getFunctionData(stack, ID);
        if (!data.contains(KEY_BOUND_UUID)) return null;
        try {
            return data.getUUID(KEY_BOUND_UUID);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getBoundPlayerName(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        CompoundTag data = LivingItemManager.getFunctionData(stack, ID);
        return data.contains(KEY_BOUND_NAME) ? data.getString(KEY_BOUND_NAME) : null;
    }

    public static void setBoundPlayer(ItemStack stack, UUID uuid, String name) {
        CompoundTag data = LivingItemManager.getFunctionData(stack, ID).copy();
        data.putUUID(KEY_BOUND_UUID, uuid);
        data.putString(KEY_BOUND_NAME, name);
        LivingItemManager.setFunctionData(stack, ID, data);
    }

    public static void clearBoundPlayer(ItemStack stack) {
        CompoundTag data = LivingItemManager.getFunctionData(stack, ID).copy();
        data.remove(KEY_BOUND_UUID);
        data.remove(KEY_BOUND_NAME);
        LivingItemManager.setFunctionData(stack, ID, data);
    }

    @Override
    public void addToTooltip(CompoundTag functionData, net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<Component> tooltipAdder, TooltipFlag flag,
                             ItemStack stack) {
        super.addToTooltip(functionData, context, tooltipAdder, flag, stack);
        EnderChannelComponent.buildTooltip(stack, functionData, tooltipAdder, flag);
    }
}