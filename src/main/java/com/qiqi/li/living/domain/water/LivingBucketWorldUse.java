package com.qiqi.li.living.domain.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 活桶的世界侧交互（流体侧批次三补丁，2026-10-04）—— **取水闭环的 priming**。
 *
 * <h3>为什么必须有这个</h3>
 * <p>桶源退役（idea.md §〇.5）后，容器内源的唯一入口 = 倒水（满活桶），而满活桶的唯一入口
 * = 汲水（需要已有源）—— <b>取水闭环断链</b>：新活空桶永远得不到第一桶水，汲/倒全部
 * 永远不触发（游戏实测「右键仍走原版」的真实根因，见 idea.md §〇.7）。活化的
 * {@code WATER_BUCKET} 是惰性物品（{@code canApply} 只认 {@code Items.BUCKET}）；
 * 活空桶对世界取水走原版 {@code BucketItem.use} 会 <b>new ItemStack 换掉整个物品</b>
 * —— 活标记与内容组件全丢。</p>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li><b>空活桶对准世界流体源（含岩浆）右键</b> → 灌入一桶（原版取水语义），
 *       <b>不换物品</b> —— 只改 {@code LIVING_BUCKET_FLUID} 内容组件，活标记保留；</li>
 *   <li><b>满活桶对世界右键</b> → 本期一律取消：原版放水同样会 swap 到空桶丢活标记，
 *       「往世界放水」尚未实现（守恒律本就是容器内经济，世界侧只保留取水入口）。</li>
 * </ul>
 *
 * <p>两端都取消（客户端拦预测 / 服务端拦真实），内容变更只在<b>服务端</b>做（权威），
 * 随槽位同步回客户端。</p>
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
        HitResult hit = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.isSource()) return;

        if (LivingBucketFunction.isEmptyBucket(held)) {
            event.setCanceled(true);   // 两端取消：防原版 swap 丢活标记
            if (!level.isClientSide) {
                fillFromSource(held, fluidState);
            }
        } else if (LivingBucketFunction.hasFullBucket(held)) {
            event.setCanceled(true);   // 满桶对世界：原版放水会丢活标记，本期一律不放
        }
    }

    /** 灌入一桶（服务端权威；包级私有供测试）。 */
    static void fillFromSource(ItemStack held, FluidState fluidState) {
        LivingBucketFunction.setContent(held, SimpleFluidContent.copyOf(
            new FluidStack(fluidState.getType(), FluidType.BUCKET_VOLUME)));
    }

}
