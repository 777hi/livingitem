package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;

/**
 * 活种子右键活耕地 → 种植（写入作物类型标记）。
 *
 * <p>交互注册为通配条目（trigger=null）+ triggerFilter 组合过滤
 * （{@link #canPlantWith}：活种子 + 可种植 + 种子数 ≥ 耕地堆叠数）——
 * 只拦「种得成」的光标组合，数量不足时原版交换照常。</p>
 *
 * <p><b>种子消耗</b>（2026-09-13 第五轮定稿）：消耗 = 活耕地堆叠数量——
 * 一堆 16 块耕地消耗 16 个活种子；数量不足无法种植。</p>
 *
 * <p>校验统一：创造模式光标经 GuiInteractionPacket.carriedTag 已在服务端恢复，
 * 与生存模式走同一套校验。已种植的耕地不可重复种植；要换作物先取消活化
 * （LivingTagPacket 活化开关，取消活化即清空种植数据）再重新种植。</p>
 */
public class PlantCropHandler implements InteractionHandler {

    /**
     * 客户端拦截门槛 + 服务端校验共用：活种子 + 可种植 + 种子数 ≥ 耕地堆叠数。
     * triggerFilter 谓词（BiPredicate：trigger=光标种子, target=耕地槽位）。
     */
    public static boolean canPlantWith(ItemStack trigger, ItemStack target) {
        if (trigger.isEmpty() || !LivingItemManager.isLivingItem(trigger)) return false;
        if (!CropClassifier.isSeedPlantableOnFarmland(trigger)) return false;
        return trigger.getCount() >= target.getCount();   // 一堆耕地消耗等量种子
    }

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack farmland = targetSlot.getItem();
        if (farmland.isEmpty() || !farmland.is(Items.FARMLAND)) return;
        if (!LivingItemManager.isLivingItem(farmland)) return;

        FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(farmland);
        if (plant.isPlanted()) return;   // 已种植

        // 创造模式光标经 carriedTag 已在服务端恢复（GuiInteractionPacket.handle），
        // 与生存模式走同一套校验
        ItemStack carried = player.containerMenu.getCarried();
        if (!canPlantWith(carried, farmland)) return;

        // 种植：写入作物类型 + 冻结 maxAge
        var cropBlock = CropClassifier.getBlockFromSeed(carried);
        if (cropBlock == null) return;
        int maxAge = CropClassifier.getMaxAge(cropBlock);

        LivingItemManager.setFarmlandPlant(farmland, plant.withCropSeed(carried.getItem(), maxAge));
        targetSlot.set(farmland);

        // 消耗 = 耕地堆叠数量的活种子（创造免费）
        if (!player.isCreative()) {
            carried.shrink(farmland.getCount());
            if (carried.isEmpty()) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
        player.containerMenu.broadcastChanges();
    }
}
