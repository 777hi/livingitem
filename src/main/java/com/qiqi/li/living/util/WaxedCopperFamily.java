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
 * <p><b>2026-10-09 收拢完成</b>：原留在 {@code LivingWaxedCopperFunction} 的「子集判定」
 * （{@code isWaxedBase} / {@code isWaxedChiseled} / {@code isWaxedCut} / {@code isWaxedGrate} /
 * {@code isWaxedBulb} / {@code getOxidationLevel}）已一并迁到本类 —— 它们已被
 * {@code ContainerEnergyStorage}、{@code LivingItemClient}、{@code LivingWaxedChiseledDecorator}
 * 等 power 域<b>之外</b>的类使用，符合本类原 javadoc 写明的迁入条件。</p>
 *
 * <p>⚠️ <b>唯一未迁的</b>是 {@code getCoilForm} —— 它把形态映射成
 * {@code LivingWaxedGeneratorData.FORM_*} 常量（power 域类型），迁到 L1 会造出
 * <b>L1 → L3 反向依赖</b>，故留在 {@code domain/power}（它只对 power 域有意义）。</p>
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

    /** 涂蜡铜块本体（1 线圈 × 4 向全叠加） */
    public static boolean isWaxedBase(Item item) {
        return item == Items.WAXED_COPPER_BLOCK || item == Items.WAXED_EXPOSED_COPPER
            || item == Items.WAXED_WEATHERED_COPPER || item == Items.WAXED_OXIDIZED_COPPER;
    }

    /** 涂蜡雕文（1 线圈 × 1 向，输入/输出双方向 WASD 配置） */
    public static boolean isWaxedChiseled(Item item) {
        return item == Items.WAXED_CHISELED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CHISELED_COPPER || item == Items.WAXED_OXIDIZED_CHISELED_COPPER;
    }

    /** 涂蜡切制（2 线圈 H/V 隔离） */
    public static boolean isWaxedCut(Item item) {
        return item == Items.WAXED_CUT_COPPER || item == Items.WAXED_EXPOSED_CUT_COPPER
            || item == Items.WAXED_WEATHERED_CUT_COPPER || item == Items.WAXED_OXIDIZED_CUT_COPPER;
    }

    /** 涂蜡格栅（1 线圈 × 4 向 + 频率过滤，过滤待定） */
    public static boolean isWaxedGrate(Item item) {
        return item == Items.WAXED_COPPER_GRATE || item == Items.WAXED_EXPOSED_COPPER_GRATE
            || item == Items.WAXED_WEATHERED_COPPER_GRATE || item == Items.WAXED_OXIDIZED_COPPER_GRATE;
    }

    /** 涂蜡铜灯（电池，专职储能不发电） */
    public static boolean isWaxedBulb(Item item) {
        return item == Items.WAXED_COPPER_BULB || item == Items.WAXED_EXPOSED_COPPER_BULB
            || item == Items.WAXED_WEATHERED_COPPER_BULB || item == Items.WAXED_OXIDIZED_COPPER_BULB;
    }

    /** 锈蚀档位 0~3（用于铜块网络分组，同等级才互通） */
    public static int getOxidationLevel(Item item) {
        if (item == Items.WAXED_COPPER_BLOCK || item == Items.WAXED_CHISELED_COPPER
            || item == Items.WAXED_CUT_COPPER || item == Items.WAXED_COPPER_GRATE
            || item == Items.WAXED_COPPER_BULB) {
            return 0;
        }
        if (item == Items.WAXED_EXPOSED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_EXPOSED_CUT_COPPER || item == Items.WAXED_EXPOSED_COPPER_GRATE
            || item == Items.WAXED_EXPOSED_COPPER_BULB) {
            return 1;
        }
        if (item == Items.WAXED_WEATHERED_COPPER || item == Items.WAXED_WEATHERED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CUT_COPPER || item == Items.WAXED_WEATHERED_COPPER_GRATE
            || item == Items.WAXED_WEATHERED_COPPER_BULB) {
            return 2;
        }
        return 3;
    }
}
