package com.qiqi.li.client;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.LivingFunctionData;
import com.qiqi.li.living.LivingItemManager;

/**
 * 活物品 Tooltip 处理器（客户端）。
 *
 * 工作流程：
 * 1. 监听 NeoForge 的 {@link ItemTooltipEvent} 事件
 * 2. 检查物品是否为活物品（LivingItemManager.isLivingItem）
 * 3. 如果是活物品：
 *    - 向 tooltip 添加"--- Living Item ---"标题
 *    - 从物品的 LIVING_FUNCTION_DATA 组件获取功能数据
 *    - 调用 LivingFunctionData.addToTooltip 渲染各功能状态
 *
 * 为什么仍然需要这个事件处理器：
 * Minecraft 只对内置的 DataComponent（如 ENCHANTMENTS、LORE、TRIM 等）
 * 自动调用 addToTooltip，自定义组件不会被自动处理。
 * 所以我们仍需要手动监听 ItemTooltipEvent 来显示活物品的状态信息。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public class LivingItemTooltip {

    /**
     * 处理物品 tooltip 渲染时的回调。
     * 由 NeoForge 在客户端每次需要显示物品 tooltip 时触发。
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (!LivingItemManager.isLivingItem(stack)) return;

        Item.TooltipContext context = Item.TooltipContext.EMPTY;
        TooltipFlag flag = event.getFlags();
        Consumer<Component> tooltipAdder = event.getToolTip()::add;

        // 空行 —— 与物品基础信息（名称、附魔等）分开，形成视觉分组
        tooltipAdder.accept(Component.nullToEmpty(""));
        // 活物品标题
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.title"));

        // 从 LIVING_FUNCTION_DATA 组件获取功能数据，让 LivingFunctionData 遍历并渲染各功能的 tooltip
        LivingFunctionData functionData = stack.get(LivingItemManager.LIVING_FUNCTION_DATA.value());
        if (functionData != null && !functionData.isEmpty()) {
            functionData.addToTooltip(context, tooltipAdder, flag);
        }
    }
}