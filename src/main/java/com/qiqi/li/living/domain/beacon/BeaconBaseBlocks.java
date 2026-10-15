package com.qiqi.li.living.domain.beacon;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * 活信标底座矿物块判定 —— 材质口径对齐原版 {@code BeaconBlockEntity.updateBase}：
 * 只看 {@code BlockTags.BEACON_BASE_BLOCKS}（铁/金/钻石/绿宝石/下界合金块），
 * <b>不看材质差异</b> ⇒ 不同列可用不同底座材料，同列（同一槽）只能是一种材料。
 *
 * <p>「柱高」= 该槽底座矿物块的堆叠数（混材质按列合计的语义即由此实现：
 * 每列独立判定、各列可不同材质）。</p>
 */
public final class BeaconBaseBlocks {

    private BeaconBaseBlocks() {
    }

    /** 该物品栈是否为底座矿物块（空栈 / 非方块物品 ⇒ false）。 */
    public static boolean isBaseBlock(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        return blockItem.getBlock().builtInRegistryHolder().is(BlockTags.BEACON_BASE_BLOCKS);
    }

    /** 该槽柱高：底座矿物块的堆叠数（非底座 / 空栈 ⇒ 0）。 */
    public static long columnHeight(ItemStack stack) {
        if (stack.isEmpty() || !isBaseBlock(stack)) return 0;
        return stack.getCount();
    }
}
