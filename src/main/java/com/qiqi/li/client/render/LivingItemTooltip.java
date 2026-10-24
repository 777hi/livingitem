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

/**
 * 活物品 tooltip 的统一渲染入口。
 *
 * <p>监听 {@code ItemTooltipEvent}，从当前屏幕的悬停槽位取出运行时数据（{@link RuntimeSegments}），
 * 再遍历所有活物品功能调用其 {@code addToTooltip} 输出「活物品」段（标题 + 各功能追加的内容行）。</p>
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = LivingItem.MOD_ID)
public class LivingItemTooltip {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (!LivingItemManager.isLivingItem(stack)) return;

        // 悬停槽位定位（文本层 / 信标面板 / 发电机面板三处共用同一个 helper）
        RuntimeSegments runtimeData = resolveHoveredRuntimeData(stack);
        LivingItemClientCache.setCurrentTooltipData(runtimeData);
        try {
            renderSection(LivingItemManager.getAllFunctions(), Item.TooltipContext.EMPTY,
                event.getFlags(), stack, event.getToolTip()::add);
        } finally {
            LivingItemClientCache.clearCurrentTooltipData();
        }
    }

    /**
     * 悬停槽位的运行时数据 —— 文本层 / 活信标 9 柱面板 / 发电机仪器面板三处共用。
     *
     * <p>⚠️ <b>路由按悬停槽位的容器，不按屏幕类型</b>（2026-10-18 修）：
     * 末影箱 GUI 里同时有末影箱槽与背包槽（任何容器 GUI 都有背包分栏），按屏幕二分会把
     * 背包槽也送进通用缓存 ⇒ 在容器界面里悬停<b>自己背包</b>的活物品，读到的是末影箱
     * 数据 ⇒ 面板错配。故按 {@code slot.container} 分：玩家背包（任何 GUI 里的背包
     * 分栏都算）读背包缓存，其余读通用缓存。</p>
     *
     * <p>末影箱 vs 普通箱子<b>不需要分辨</b>：① 服务端对末影箱包做了 viewer 门控
     * （{@code ContainerViewers.of} 的末影箱分支）⇒ 通用缓存里只有「当前打开的
     * 那个非背包容器」在写；② 客户端本就分不清两者——末影箱 GUI 在客户端就是
     * {@code GENERIC_9x3} + 27 格 {@code SimpleContainer} 替身，与单箱同形
     * （{@code FluidFlowClientCache.RenderTarget} 注释实证：任何
     * {@code instanceof PlayerEnderChestContainer} 的客户端判据都是死分支）。</p>
     */
    public static RuntimeSegments resolveHoveredRuntimeData(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            Slot hoveredSlot = containerScreen.getSlotUnderMouse();
            if (hoveredSlot != null && hoveredSlot.getItem() == stack) {
                return isBackpackSlot(mc, hoveredSlot)
                    ? LivingItemClientCache.getPlayer(hoveredSlot.getContainerSlot())
                    : LivingItemClientCache.get(hoveredSlot.getContainerSlot());
            }
        }
        // 兜底：特殊 GUI（如创造模式物品栏 tab）的槽位索引与 Inventory 不对齐时，
        // 按物品引用在玩家背包中定位——玩家背包的 wrapper 索引 = Inventory 索引。
        if (mc.player != null) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i) == stack) {
                    return LivingItemClientCache.getPlayer(i);
                }
            }
        }
        return RuntimeSegments.EMPTY;
    }

    /** 悬停槽是否属于玩家背包 —— 任何容器 GUI（含末影箱）里的背包分栏都算。 */
    private static boolean isBackpackSlot(Minecraft mc, Slot slot) {
        return mc.player != null && slot.container == mc.player.getInventory();
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