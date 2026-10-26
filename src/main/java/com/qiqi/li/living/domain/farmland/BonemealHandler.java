package com.qiqi.li.living.domain.farmland;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.InteractionHandler;

/**
 * 活骨粉右键活耕地 → 强制触发一次生长 tick。
 *
 * <p>交互注册为精确触发器条目（trigger=BONE_MEAL），与种植通配条目共用
 * target/button，靠 findInteraction「精确优先于通配」两趟匹配分流。</p>
 *
 * <p><b>语义（2026-09-13 第五轮定稿：一切行为由生长 tick 决定，骨粉只是触发器）</b>：
 * 立即执行一次必定成功的生长 tick——未成熟 → age+1；成熟且待输出为空 →
 * 冻结实³战利品表（输出阶段每 tick 自动运输，下个 tick 送达生长槽）。
 * 组件无实际变化（如已冻结的成熟耕地重复右键）不消耗骨粉。</p>
 */
public class BonemealHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack farmland = targetSlot.getItem();
        if (farmland.isEmpty() || !farmland.is(Items.FARMLAND)) return;
        if (!LivingItemManager.isLivingItem(farmland)) return;

        FarmlandPlantComponent plant = FarmlandPlantComponent.of(farmland);
        if (!plant.isPlanted()) return;

        Block cropBlock = CropClassifier.getBlockFromSeed(plant.cropSeed());
        if (cropBlock == null) return;

        // ⚠️ 光标校验对创造模式同样生效（2026-10-27 审查 P3）：信任边界在 handler ——
        //    targetIdentityAccepted 只复验目标，触发物全靠这里自查；曾创造模式连
        //    「光标是不是活骨粉」都不验 ⇒ 改包客户端可空手刷生长。与同域
        //    PlantCropHandler / TillToFarmlandHandler 的「照验免扣」口径对齐。
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty() || !carried.is(Items.BONE_MEAL)
            || !LivingItemManager.isLivingItem(carried)) return;
        boolean creative = player.isCreative();

        // 强制触发一次生长 tick（必定成功的概率判定体）——与容器 tick 概率成功后
        // 走的是同一段逻辑（LivingFarmlandFunction.forceGrowthTick）
        FarmlandPlantComponent updated = LivingFarmlandFunction.forceGrowthTick(plant, cropBlock,
            (net.minecraft.server.level.ServerLevel) player.level());
        if (updated.equals(plant)) return;   // 无实际变化（已冻结的成熟耕地）不消耗骨粉

        FarmlandPlantComponent.set(farmland, updated);
        targetSlot.set(farmland);

        if (!creative) {
            carried.shrink(1);
            if (carried.isEmpty()) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
        player.containerMenu.broadcastChanges();
    }
}
