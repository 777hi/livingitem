package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import com.qiqi.li.living.domain.farmland.LivingFarmlandFunction;

/**
 * 活骨粉右键已种植未成熟的活耕地 → 催熟。
 *
 * <p>交互注册为精确触发器条目（trigger=BONE_MEAL），与种植通配条目
 * （trigger=null）共用 target/button，靠 findInteraction「精确优先于通配」
 * 两趟匹配分流（2026-09-13 遮蔽事故后修正，见 InteractionRegistry javadoc）。</p>
 *
 * <p>加成 +2~5 级（Mth.nextInt 双闭区间，原版 StemBlock.performBonemeal 同公式）。
 * 催到 maxAge 时立即冻结战利品表（LivingFarmlandFunction.tryFreezeDrops），
 * 不等下一个冷却周期——否则玩家催熟后要空转 ≤10 秒才见产出。
 * BLOCKED 态（生长槽被占）也可催熟——BLOCKED 只是生长暂停，产出仍走等待机制。</p>
 */
public class BonemealHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack farmland = targetSlot.getItem();
        if (farmland.isEmpty() || !farmland.is(Items.FARMLAND)) return;
        if (!LivingItemManager.isLivingItem(farmland)) return;

        FarmlandPlantComponent plant = LivingItemManager.getFarmlandPlant(farmland);
        if (!plant.isPlanted() || plant.isMature()) return;

        if (!player.isCreative()) {
            ItemStack carried = player.containerMenu.getCarried();
            if (carried.isEmpty() || !carried.is(Items.BONE_MEAL)
                || !LivingItemManager.isLivingItem(carried)) return;
            carried.shrink(1);
            if (carried.isEmpty()) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }

        // +2~5 级，不超过 maxAge（原版 performBonemeal 同公式：Mth.nextInt 双闭区间。
        // 勘误：旧实现用 RandomSource.nextInt(2,5) 上界排除 = +2~4）
        int boost = Mth.nextInt(player.level().random, 2, 5);
        int newAge = Math.min(plant.age() + boost, plant.maxAge());
        plant = plant.withAge(newAge);

        // 催到成熟 → 立即冻结战利品表（不等下个冷却周期）
        if (plant.isMature()) {
            Block cropBlock = CropClassifier.getBlockFromSeed(plant.cropSeed());
            if (cropBlock != null) {
                plant = LivingFarmlandFunction.tryFreezeDrops(plant, cropBlock, (net.minecraft.server.level.ServerLevel) player.level());
            }
        }

        LivingItemManager.setFarmlandPlant(farmland, plant);
        targetSlot.set(farmland);
        player.containerMenu.broadcastChanges();
    }
}
