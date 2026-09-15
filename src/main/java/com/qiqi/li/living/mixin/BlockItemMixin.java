package com.qiqi.li.living.mixin;

import com.qiqi.li.living.domain.farmland.LivingFarmlandPlacement;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 活耕地「放置回世界」：放置已种植的活耕地后，把物品自带的作物种到耕地之上。
 *
 * <h3>为什么注入点必须是 {@code consume} 之前</h3>
 * <p>{@code BlockItem.place} 在成功分支的<b>末尾</b>才 {@code itemstack.consume(1, player)}。
 * 若注入在 {@code @At("RETURN")}，单块放置（count 1→0）后 {@code ItemStack.isEmpty()} 为真，
 * 而空栈的 {@code getComponents()} 返回 {@code DataComponentMap.EMPTY} ⇒ 组件读不到、
 * <b>静默失效</b>；堆叠放置却正常——这种「只在单块时坏」的 bug 极难排查。
 * 锚在 {@code consume} 之前：此时 {@code placeBlock} 已成功（放置成败已确定），
 * 且物品栈尚未被扣减（组件可读）。</p>
 *
 * <p>该 {@code consume} 在 {@code BlockItem} 全文中唯一，故无需 {@code ordinal}。</p>
 *
 * <p>本 Mixin <b>不做任何判断</b>，只做转调——守卫、客户端判定与异常兜底全在
 * {@link LivingFarmlandPlacement#onPlaced} 内（保持无状态）。</p>
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V"))
    private void onPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // getClickedPos() 即耕地所在格（BlockItem.place 用它落方块）
        LivingFarmlandPlacement.onPlaced(
            context.getLevel(), context.getClickedPos(), context.getItemInHand(), context.getPlayer());
    }
}
