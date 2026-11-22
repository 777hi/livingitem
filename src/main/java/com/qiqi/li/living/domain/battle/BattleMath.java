package com.qiqi.li.living.domain.battle;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * 战斗数值换算（纯函数，§4.2 血量统一模型）。
 *
 * <pre>
 * 单件血量 = 硬度 &gt; 0 ? max(1, round(硬度 × 10)) : 20（非方块 / 无硬度 20；负硬度按 50 档）
 * 总血量   = 单件 × count − 当前层已损；每掉满单件血量 ⇒ count − 1（掉一层）
 * </pre>
 */
public final class BattleMath {

    private BattleMath() {}

    /** 单件血量（§4.2：硬度 × 10，黑曜石 50→500 / 圆石 2→20 / 玻璃 0.3→3 / 非方块 20）。 */
    public static int perPiece(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            // getDestroySpeed 的原版实现仅返回字段、不解引用参数 ⇒ 无世界上下文传 null 安全
            float hardness = blockItem.getBlock().defaultBlockState().getDestroySpeed(null, null);
            if (hardness < 0) return 50;   // 不可破坏档（基岩类）按黑曜石同档
            if (hardness == 0) return 20;  // 无硬度
            return Math.max(1, Math.round(hardness * 10));
        }
        return 20;
    }

    /** 一次结算的结果：层数变化 + 新的当前层已损。 */
    public record Outcome(int newCount, int newLayerDamage, int layersLost, boolean destroyed) {}

    /**
     * 结算一次伤害：当前层扛住则累加；溢出按整层下滚（余数进新当前层）；
     * 层数掉光 = 棋子被吃光（destroyed ⇒ 物品消失）。
     */
    public static Outcome applyDamage(int perPiece, int count, int layerDamage, int amount) {
        int amountInt = Math.max(0, amount);
        int currentLeft = perPiece - layerDamage;           // 当前层剩余血
        if (amountInt < currentLeft) {
            return new Outcome(count, layerDamage + amountInt, 0, false);
        }
        int overflow = amountInt - currentLeft;             // 溢出到后续层
        int layersLost = overflow / perPiece + 1;           // 当前层 + 溢出整层
        int newCount = count - layersLost;
        if (newCount <= 0) {
            return new Outcome(0, 0, count, true);
        }
        return new Outcome(newCount, overflow % perPiece, layersLost, false);
    }
}
