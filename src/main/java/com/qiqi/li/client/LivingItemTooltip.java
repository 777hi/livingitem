package com.qiqi.li.client;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.LivingItemFunction;
import com.qiqi.li.living.LivingItemManager;

/**
 * 活物品 Tooltip 处理器（客户端）。
 *
 * 设计原则：完全委托给各活物品功能自行渲染状态信息
 *
 * 工作流程：
 * 1. 监听 NeoForge 的 {@link ItemTooltipEvent} 事件
 * 2. 检查物品是否为活物品
 * 3. 如果是活物品：
 *    - 添加活物品通用标题
 *    - 遍历所有已注册的活物品功能
 *    - 对每个匹配的功能调用其 addToTooltip 方法
 *    - 各功能内部再委托给对应的组件进行详细渲染
 *
 * 架构优势：
 * - 去中心化：每个功能负责自己的数据显示逻辑
 * - 可扩展：新增功能无需修改此处理器
 * - 组件化：具体内容由 ILivingComponent.appendTooltip 实现
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public class LivingItemTooltip {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (!LivingItemManager.isLivingItem(stack)) return;

        Item.TooltipContext context = Item.TooltipContext.EMPTY;
        TooltipFlag flag = event.getFlags();
        Consumer<Component> tooltipAdder = event.getToolTip()::add;

        // 活物品通用标题
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.title"));

        // 遍历所有已注册的功能，让它们各自渲染自己的状态
        for (LivingItemFunction function : LivingItemManager.getAllFunctions()) {
            if (function.canApply(stack)) {
                // 从物品获取该功能的数据
                CompoundTag functionData = LivingItemManager.getFunctionData(stack, function.getFunctionId());
                
                // 委托给功能对象渲染tooltip（功能内部会进一步委托给组件）
                function.addToTooltip(functionData, context, tooltipAdder, flag);
            }
        }
    }
}