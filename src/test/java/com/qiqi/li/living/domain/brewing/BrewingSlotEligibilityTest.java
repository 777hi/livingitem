package com.qiqi.li.living.domain.brewing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活酿造台的槽位准入（隔离规则）单测。
 *
 * <p><b>守的规则</b>：<b>活物品不作材料 / 瓶子 / 燃料</b>（与活熔炉 / 活漏斗同族）——
 * 材料会被 {@code consumeOne} 吃掉、瓶子会被 {@code mix} <b>原地转化</b>（丢掉活标记与内部状态）、
 * 燃料会被消耗 ⇒ 三处都必须挡在门外。</p>
 *
 * <p>只钉纯判据（{@link LivingBrewingStandFunction#isEligibleBottle} /
 * {@code isEligibleIngredient}）；「是不是有效材料 / 能不能配」需要真实 {@code PotionBrewing}
 * （依赖 registry）⇒ 材料侧只测「活物品被短路拒绝」这一半 —— 那正是本次修的 bug。</p>
 */
class BrewingSlotEligibilityTest {

    private static ItemStack living(Item item) {
        ItemStack stack = new ItemStack(item);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("活物品不能作瓶子（会被 mix 原地转化掉）")
    void livingItemIsNotEligibleBottle() {
        assertFalse(LivingBrewingStandFunction.isEligibleBottle(living(Items.POTION)),
            "带活标记的药水不该被当瓶子酿 —— mix 会原地替换它，活标记与内部状态全丢");
        assertFalse(LivingBrewingStandFunction.isEligibleBottle(living(Items.FURNACE)),
            "任何活物品都不该被当瓶子");
    }

    @Test
    @DisplayName("普通物品仍通过「瓶子准入」（是否真能酿由 hasMix 决定）")
    void plainItemStillPassesBottleGate() {
        assertTrue(LivingBrewingStandFunction.isEligibleBottle(new ItemStack(Items.POTION)));
        assertTrue(LivingBrewingStandFunction.isEligibleBottle(new ItemStack(Items.GLASS_BOTTLE)),
            "准入只判「非空 + 非活物品」；空玻璃瓶是否可酿由 hasMix 决定（原版语义）");
        assertFalse(LivingBrewingStandFunction.isEligibleBottle(ItemStack.EMPTY), "空槽不算瓶子");
    }

    @Test
    @DisplayName("活物品不能作材料（会被 consumeOne 吃掉）")
    void livingItemIsNotEligibleIngredient() {
        // 判据顺序：非空 → 非活物品 → isIngredient ⇒ 活物品在第二步短路，不会碰 PotionBrewing
        // （故可传 null，单测无需 registry）
        assertFalse(LivingBrewingStandFunction.isEligibleIngredient(null, living(Items.REDSTONE)),
            "活红石不该被当酿造材料消耗（本次修的 bug）");
        assertFalse(LivingBrewingStandFunction.isEligibleIngredient(null, living(Items.NETHER_WART)),
            "活地狱疣同理");
    }

    @Test
    @DisplayName("空槽不是材料")
    void emptyIsNotIngredient() {
        assertFalse(LivingBrewingStandFunction.isEligibleIngredient(null, ItemStack.EMPTY));
    }
}
