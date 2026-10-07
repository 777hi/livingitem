package com.qiqi.li.living.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 涂蜡铜块家族（发电机体 + 电池，共 20 件）的物品分类谓词。
 *
 * <p><b>2026-10-08 从 {@code domain/power/LivingWaxedCopperFunction} 上移到 L1 基础层。</b>
 * 它只做「这个 {@code Item} 是不是涂蜡铜块家族」的纯判断（硬编码 20 个 {@code Items}），
 * 却同时被 {@code domain/redstone/ContainerRedstoneData}（信号层的铜槽位判定）与
 * {@code LivingItemClient}（客户端渲染分支）使用 —— 留在 power 域等于让「redstone 依赖 power」，
 * 那是<b>放错盒子</b>（R3 领域互依赖）。</p>
 *
 * <p>⚠️ <b>家族分类尚未完全收拢</b>：{@code isWaxedBase} / {@code isWaxedChiseled} /
 * {@code isWaxedCut} / {@code isWaxedGrate} / {@code isWaxedBulb} / {@code getCoilForm}
 * 等「子集判定」仍留在 {@code LivingWaxedCopperFunction}（它们目前只被 power 域使用）。
 * 若将来其它域也需要，应一并迁到本类。</p>
 */
public final class WaxedCopperFamily {

    private WaxedCopperFamily() {
    }

    /** 全部涂蜡铜块家族（发电机体 + 电池），共 20 件。 */
    public static boolean isWaxedCopperBlock(Item item) {
        return item == Items.WAXED_COPPER_BLOCK || item == Items.WAXED_EXPOSED_COPPER
            || item == Items.WAXED_WEATHERED_COPPER || item == Items.WAXED_OXIDIZED_COPPER
            || item == Items.WAXED_CHISELED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CHISELED_COPPER || item == Items.WAXED_OXIDIZED_CHISELED_COPPER
            || item == Items.WAXED_CUT_COPPER || item == Items.WAXED_EXPOSED_CUT_COPPER
            || item == Items.WAXED_WEATHERED_CUT_COPPER || item == Items.WAXED_OXIDIZED_CUT_COPPER
            || item == Items.WAXED_COPPER_GRATE || item == Items.WAXED_EXPOSED_COPPER_GRATE
            || item == Items.WAXED_WEATHERED_COPPER_GRATE || item == Items.WAXED_OXIDIZED_COPPER_GRATE
            || item == Items.WAXED_COPPER_BULB || item == Items.WAXED_EXPOSED_COPPER_BULB
            || item == Items.WAXED_WEATHERED_COPPER_BULB || item == Items.WAXED_OXIDIZED_COPPER_BULB;
    }
}
