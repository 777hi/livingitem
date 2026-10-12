package com.qiqi.li.client.input;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import com.qiqi.li.living.domain.farmland.PlantCropHandler;

/**
 * 活耕地种植悬停提示 —— 光标持有活种子、悬浮在活耕地上时，在鼠标处渲染一条提示。
 *
 * <p><b>为什么需要</b>：原版 {@code AbstractContainerScreen#renderTooltip} 对
 * 「光标非空」的悬停<b>不渲染任何 tooltip</b>（判据 {@code menu.getCarried().isEmpty()}）——
 * 玩家拿着活种子悬停活耕地时看不到任何说明；右键没反应（种子数不足 / 已种植）
 * 也得不到反馈。本类补上这条客户端反馈，纯展示、不发包、与服务端交互零耦合。
 *
 * <p><b>门槛与 {@link PlantCropHandler#canPlantWith} 同源</b>：提示说「种得成」的
 * 条件与交互真实门槛逐条一致（活种子 + 可种植 + 种子数 ≥ 耕地堆叠数）——
 * 数量不足时提示差额（需要 N / 持有 M），这正是原版抑制 tooltip 后玩家
 * 唯一的信息来源。
 *
 * <p><b>渲染</b>：由 {@code AbstractContainerScreenMixin} 在 {@code render} TAIL 调
 * {@link #getHintLines}，与方向配置 HUD（{@link LivingItemInputHandler#getHudLines}）
 * 同款走 {@code GuiGraphics#renderTooltip} 批量队列 ⇒ 天然排在物品图标之上。
 */
public final class FarmlandPlantHint {

    private FarmlandPlantHint() {}

    /**
     * 渲染入口：读客户端悬停状态。{@code null} = 不显示（调用方不渲染）。
     *
     * @param menu        当前容器菜单（光标物品来源）
     * @param hoveredSlot 悬停槽位（{@code AbstractContainerScreen#hoveredSlot}，可为 null）
     */
    @Nullable
    public static List<Component> getHintLines(@Nullable AbstractContainerMenu menu, @Nullable Slot hoveredSlot) {
        if (menu == null || hoveredSlot == null) return null;
        Minecraft mc = Minecraft.getInstance();
        boolean creative = mc.player != null && mc.player.isCreative();
        return tryBuildLines(menu.getCarried(), hoveredSlot.getItem(), creative);
    }

    /**
     * 纯逻辑（可单测）：构造提示行。任一门槛不过返回 {@code null}。
     *
     * <p>门槛（与 {@code plant_crop} 交互逐条对齐）：目标是<b>活耕地</b>、光标是
     * <b>活种子</b>且可种植；数量判定复用 {@link PlantCropHandler#canPlantWith}
     * 的堆叠数比较（不另写一套，防止提示与真实门槛漂移）。
     * 已种植的耕地交互本身也拒绝重复种植，但仍给一行「已种植」说明 ——
     * 否则光标非空时原版 tooltip 一并被抑制，玩家同样得不到任何反馈。
     *
     * @param carried   光标物品（种子）
     * @param farmland  悬停槽位物品（耕地）
     * @param creative  创造模式（消耗免费，仅影响文案）
     */
    @Nullable
    public static List<Component> tryBuildLines(ItemStack carried, ItemStack farmland, boolean creative) {
        if (carried.isEmpty() || farmland.isEmpty()) return null;
        if (!farmland.is(Items.FARMLAND) || !LivingItemManager.isLivingItem(farmland)) return null;
        if (!LivingItemManager.isLivingItem(carried)) return null;
        if (!CropClassifier.isSeedPlantableOnFarmland(carried)) return null;

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("hint.livingitem.farmland.title"));

        FarmlandPlantComponent plant = FarmlandPlantComponent.of(farmland);
        if (plant.isPlanted()) {
            // isPlanted() 已保证 cropSeed 非空
            lines.add(Component.translatable("hint.livingitem.farmland.planted",
                plant.cropSeed().getDescription()));
            return lines;
        }

        lines.add(Component.translatable("hint.livingitem.farmland.crop", carried.getHoverName()));
        if (PlantCropHandler.canPlantWith(carried, farmland)) {
            lines.add(Component.translatable(creative
                ? "hint.livingitem.farmland.cost_creative"
                : "hint.livingitem.farmland.cost", farmland.getCount()));
        } else {
            lines.add(Component.translatable("hint.livingitem.farmland.insufficient",
                farmland.getCount(), carried.getCount()));
        }
        return lines;
    }
}
