package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;

/**
 * 活种子右键活耕地 → 种植（写入作物类型标记，V1 语义不消耗种子）。
 *
 * <p>交互注册为通配条目（trigger=null，任意光标触发），本 handler 内校验光标：
 * 活种子（IS_LIVING + CropClassifier 可种植）。种子不被消耗——数量与耕地堆叠数
 * 无法对应，语义简化为「一个种子标记一组耕地」（设计文档）。</p>
 *
 * <p>校验统一：创造模式光标经 GuiInteractionPacket.carriedTag 在服务端恢复，
 * 因此非空光标在两种模式下走同一套「IS_LIVING + 可种植」校验——旧实现创造
 * 模式跳过可种植性校验，能把活泥土等垃圾物品「种」上形成死循环作物。</p>
 *
 * <p>已种植的耕地不可重复种植（cropSeed != null 拒绝）；要换作物先取消活化
 * （LivingTagPacket 活化开关，取消活化即清空种植数据）再重新种植。</p>
 */
public class PlantCropHandler implements InteractionHandler {

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
        if (carried.isEmpty()) return;   // 空手无种子可种
        if (!LivingItemManager.isLivingItem(carried)) return;
        if (!CropClassifier.isSeedPlantableOnFarmland(carried)) return;

        // 种植：写入作物类型 + 冻结 maxAge
        var cropBlock = CropClassifier.getBlockFromSeed(carried);
        if (cropBlock == null) return;
        int maxAge = CropClassifier.getMaxAge(cropBlock);

        LivingItemManager.setFarmlandPlant(farmland, plant.withCropSeed(carried.getItem(), maxAge));
        targetSlot.set(farmland);
        player.containerMenu.broadcastChanges();
    }
}
