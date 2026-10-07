package com.qiqi.li.client.render;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import com.qiqi.li.LivingItem;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public class LivingItemTooltip {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (!LivingItemManager.isLivingItem(stack)) return;

        // 从当前屏幕获取悬停槽位，查找对应的运行时数据（用于 tooltip 渲染）。
        // 玩家背包 GUI 的遥测存放在独立的 player 缓存（服务端背包包直发本人，
        // 与 BE 容器缓存分离避免串台，v19.1）。
        RuntimeSegments runtimeData = RuntimeSegments.EMPTY;
        Minecraft mc = Minecraft.getInstance();
        boolean playerGui = mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
            || mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            Slot hoveredSlot = containerScreen.getSlotUnderMouse();
            if (hoveredSlot != null && hoveredSlot.getItem() == stack) {
                runtimeData = playerGui
                    ? LivingItemClientCache.getPlayer(hoveredSlot.getContainerSlot())
                    : LivingItemClientCache.get(hoveredSlot.getContainerSlot());
            }
        }
        // 兜底：特殊 GUI（如创造模式物品栏 tab）的槽位索引与 Inventory 不对齐时，
        // 按物品引用在玩家背包中定位——玩家背包的 wrapper 索引 = Inventory 索引。
        if (runtimeData == RuntimeSegments.EMPTY && mc.player != null) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i) == stack) {
                    runtimeData = LivingItemClientCache.getPlayer(i);
                    break;
                }
            }
        }
        LivingItemClientCache.setCurrentTooltipData(runtimeData);
        try {
            renderSection(LivingItemManager.getAllFunctions(), Item.TooltipContext.EMPTY,
                event.getFlags(), stack, event.getToolTip()::add);
        } finally {
            LivingItemClientCache.clearCurrentTooltipData();
        }
    }

    /**
     * 输出「活物品」段：<b>标题无条件输出</b>，内容行由各功能追加。
     *
     * <p>🔴 <b>定稿（2026-10-07，用户拍板「标题不要删，标题是正常的」）</b>：
     * 「--- 活物品 ---」是<b>「这个物品是活物品」标记</b>，不是内容行 ——
     * 哪怕功能一行都不产出（活桶：形态本身就是信息，定稿不写 tooltip），
     * 标题与它上方的分隔空行<b>照旧输出</b>。
     * （曾试过「零行则整段不输出」的惰性标题，已否：会把"这是活物品"这个标记一起丢掉。）</p>
     *
     * <p>{@code functions} 由调用方传入（本项目传 {@link LivingItemManager#getAllFunctions()}），
     * 便于单测喂 stub 功能列表。</p>
     *
     * @param functions 候选功能列表（按注册顺序）
     * @param sink      最终 tooltip 输出器
     */
    static void renderSection(List<LivingItemFunction> functions,
                              Item.TooltipContext context,
                              TooltipFlag flag,
                              ItemStack stack,
                              Consumer<Component> sink) {
        sink.accept(Component.nullToEmpty(""));
        sink.accept(Component.translatable("tooltip.livingitem.title"));
        for (LivingItemFunction function : functions) {
            if (function.canApply(stack)) {
                function.addToTooltip(context, sink, flag, stack);
            }
        }
    }
}