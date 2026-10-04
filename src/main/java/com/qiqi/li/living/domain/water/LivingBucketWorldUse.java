package com.qiqi.li.living.domain.water;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 活桶的世界侧交互（2026-10-04）—— **取水闭环的 priming**，逻辑直接复用原版桶。
 *
 * <h3>为什么必须有这个</h3>
 * <p>桶源退役（idea.md §〇.5）后，容器内源的唯一入口 = 倒水（满活桶），而满活桶的唯一入口
 * = 汲水（需要已有源）—— 取水闭环断链。活空桶对世界取水走原版 {@code BucketItem.use}
 * 会换掉整个物品（丢活标记）⇒ 必须拦截并以「保留活标记」的方式重放原版逻辑。</p>
 *
 * <h3>行为（对齐原版 {@code BucketItem.use} 的取水分支）</h3>
 * <ul>
 *   <li><b>空活桶对准可拾取方块</b>（{@link BucketPickup}——原版水/岩浆方块与一切模组实现）
 *       右键 → {@code pickupBlock} 取水（<b>原版语义：拿走世界水源方块</b>），
 *       结果桶物品 + 保留活标记；数量/创造模式语义复用 {@link ItemUtils#createFilledResult}；</li>
 *   <li><b>满活桶对世界右键</b> → 一律取消：原版放水会 swap 丢活标记，
 *       「往世界放水」（应复用 {@code emptyContents}）后补。</li>
 * </ul>
 */
@EventBusSubscriber
public final class LivingBucketWorldUse {

    private LivingBucketWorldUse() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack held = event.getItemStack();
        if (!LivingBucketFunction.isLivingBucket(held)) return;

        Level level = event.getLevel();
        Player player = event.getEntity();
        BlockHitResult hit = itemPOVHit(level, player);
        if (hit == null) return;

        BlockPos pos = hit.getBlockPos();
        if (LivingBucketFunction.isEmptyBucket(held)) {
            if (!(level.getBlockState(pos).getBlock() instanceof BucketPickup pickup)) return;

            event.setCanceled(true);   // 两端取消：防原版 use 换物品丢活标记
            if (level.isClientSide) return;

            ItemStack picked = pickup.pickupBlock(player, level, pos, level.getBlockState(pos));
            if (picked.isEmpty()) return;   // 方块拒绝（原版 fail 语义）
            pickup.getPickupSound(level.getBlockState(pos))
                .ifPresent(sound -> level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F));
            level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);

            // 原版 createFilledResult 管数量/创造模式；结果物品保留活标记
            player.setItemInHand(event.getHand(),
                ItemUtils.createFilledResult(held, player, asLivingBucketForm(picked, held)));
        } else if (LivingBucketFunction.hasFullBucket(held)) {
            event.setCanceled(true);   // 满桶对世界：原版放水会丢活标记，本期一律不放
        }
    }

    /** 拾取结果 → 活桶形态：picked 的物品 + 原持桶的组件（活标记）。包级私有供测试。 */
    static ItemStack asLivingBucketForm(ItemStack pickedFilledBucket, ItemStack originalHeld) {
        ItemStack out = new ItemStack(pickedFilledBucket.getItem());
        out.applyComponents(originalHeld.getComponents());
        return out;
    }

    /** 原版同款视线投射（SOURCE_ONLY）：命中可拾取方块返回结果，否则 null。包级私有供测试。 */
    static BlockHitResult itemPOVHit(Level level, Player player) {
        HitResult hit = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        return hit.getType() == HitResult.Type.BLOCK ? (BlockHitResult) hit : null;
    }
}
