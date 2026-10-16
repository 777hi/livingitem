package com.qiqi.li.living.domain.beacon;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BeaconBeamBlock;

/**
 * 染色玻璃 → 光束颜色（U3 方案 B，2026-10-16 用户定）。
 *
 * <p>走原版同一接口 {@link BeaconBeamBlock}（染色玻璃 / 染色玻璃板 / 信标自身都实现了它；
 * 其它模组的彩色玻璃只要实现该接口也自动生效）——与 NeoForge
 * {@code IBlockExtension#getBeaconColorMultiplier} 的默认实现同源。</p>
 *
 * <p><b>服务端与客户端共用</b>：玩家宿主（背包上方那一格）服务端算好随 S2C 下发
 * （客户端读不到别人背包）；箱子宿主（箱口上方世界方块）客户端本地扫即可。</p>
 */
public final class BeaconGlassColor {

    /** 无染色玻璃时的颜色 —— 原版白光。 */
    public static final int DEFAULT = 0xFFFFFF;

    private BeaconGlassColor() {
    }

    /** 物品 → 光束颜色；非染色玻璃 / 空栈 ⇒ {@link #DEFAULT}。 */
    public static int of(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return DEFAULT;
        }
        if (blockItem.getBlock() instanceof BeaconBeamBlock beam) {
            return beam.getColor().getTextureDiffuseColor();
        }
        return DEFAULT;
    }
}
