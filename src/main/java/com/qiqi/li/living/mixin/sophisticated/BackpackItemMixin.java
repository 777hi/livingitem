package com.qiqi.li.living.mixin.sophisticated;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.qiqi.li.living.container.ContainerHandoff;
import com.qiqi.li.logging.ModLog;

/**
 * 精妙背包「<b>放置</b>」侧的容器级数据恢复（2026-10-21，真机日志驱动）。
 *
 * <h3>为什么拾取修好了水还是没了</h3>
 * <p>真机日志证明拾取侧完全正常（`HEAD 捕获` → `handoff: movers=2` → `moved=true`，
 * 数据确实写进了手里的 {@code diamond_backpack}）。问题在<b>放置</b>：</p>
 * <ul>
 *   <li>精妙<b>不是</b> {@code BlockItem}：{@code BackpackItem extends ItemBase}，放置走
 *       自己的 {@code tryPlace} 里的 {@code level.setBlockAndUpdate} ——
 *       <b>绕开了 {@code BlockItem.place}</b> ⇒ {@code BlockEvent.EntityPlaceEvent}
 *       根本不触发（我们原来唯一的放置挂点）。</li>
 *   <li>就算触发了，{@code ContainerDrops.placedItemInHand} 按
 *       {@code placedBlock.asItem()} 匹配也认不出：方块物品与
 *       {@code diamond_backpack} 是<b>两个不同 item</b>。</li>
 * </ul>
 *
 * <h3>修法：在精妙自己的放置方法上补挂点</h3>
 * <ul>
 *   <li>{@code HEAD}：手持栈<b>还没被 shrink</b> ⇒ 暂存副本（{@code stageRestore} 内部 copy）；</li>
 *   <li>{@code RETURN}：放置成功（{@code consumesAction}）⇒ 从点击位置取 BE，把数据恢复进去。</li>
 * </ul>
 *
 * <p>⚠️ <b>暂存必须 copy</b>：{@code tryPlace} 末尾 {@code getItemInHand().shrink(1)} 是<b>就地改</b>
 * 同一个栈对象，存引用会在 RETURN 时读到空栈。</p>
 *
 * <p>⚠️ 这条也覆盖<b>发射器放置</b>（{@code ModItems} 以 {@code player = null} 调 {@code tryPlace}）
 * —— 比 {@code EntityPlaceEvent} 那条路覆盖得更全。</p>
 */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem")
public abstract class BackpackItemMixin {

    @Inject(method = "tryPlace", at = @At("HEAD"), require = 0, remap = false)
    private void livingItem$stagePlacement(Player player, Direction direction, BlockPlaceContext ctx,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        if (ctx.getLevel().isClientSide) return;
        ContainerHandoff.stageRestore(ctx.getItemInHand());
    }

    @Inject(method = "tryPlace", at = @At("RETURN"), require = 0, remap = false)
    private void livingItem$restoreToBlock(Player player, Direction direction, BlockPlaceContext ctx,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return;

        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            // 没放成（canPlace 失败 / 走了 PASS 分支）⇒ 丢掉暂存，别留到下一次
            ContainerHandoff.discard();
            return;
        }

        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        boolean restored = ContainerHandoff.restoreTo(be);
        ModLog.MIXIN.info("[精妙联动] 放置恢复：pos={} be={} restored={}", pos, be, restored);
    }
}
