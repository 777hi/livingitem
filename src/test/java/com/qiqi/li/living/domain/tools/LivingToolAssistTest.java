package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 辅助挖掘的<b>速度分层</b>（2026-10-27 用户定）：物品栏顶层相加 + 背包里只取最高。
 *
 * <p>这一层是 2026-10-27 真机翻车（「方块破坏又恢复」，日志：客户端 bonus=16 / 服务端 bonus=8）
 * 的直接所在，此前没有任何回归钉住。判据是 {@link CarriedLivingItems.Carried#inContainer()}
 * —— <b>显式字段</b>，不能从槽位推断（见 {@code CarriedLivingItemsTest}）。</p>
 */
class LivingToolAssistTest {

    /** 用耐久值当速度标记（测试只关心分层数学，不关心速度怎么算）。 */
    private static float speedOf(ItemStack stack) {
        return stack.getDamageValue();
    }

    private static CarriedLivingItems.Carried top(ItemStack stack, int slot) {
        return new CarriedLivingItems.Carried(stack, slot, -1, false);
    }

    private static CarriedLivingItems.Carried inner(ItemStack stack, int innerSlot) {
        return new CarriedLivingItems.Carried(stack, 10, innerSlot, true);
    }

    @Test
    @DisplayName("顶层相加 + 背包只取最高：3 + 4（顶层）+ 9（背包最高，5 不加）= 16")
    void topLevelSums_containerTakesBestOnly() {
        ItemStack s3 = new ItemStack(Items.IRON_PICKAXE); s3.setDamageValue(3);
        ItemStack s4 = new ItemStack(Items.IRON_SHOVEL);  s4.setDamageValue(4);
        ItemStack s9 = new ItemStack(Items.DIAMOND_AXE);  s9.setDamageValue(9);
        ItemStack s5 = new ItemStack(Items.DIAMOND_SHOVEL); s5.setDamageValue(5);

        float bonus = LivingToolAssist.assistBonus(
            List.of(top(s3, 0), top(s4, 1), inner(s9, 2), inner(s5, 5)),
            LivingToolAssistTest::speedOf);

        assertEquals(16.0F, bonus, 0.0001F,
            "背包若也相加就成了「塞满铲子 = 倍速挂件」—— 只取最高那把");
    }

    @Test
    @DisplayName("空列表 ⇒ 0（不加白速度）")
    void emptyIsZero() {
        assertEquals(0.0F, LivingToolAssist.assistBonus(List.of(), LivingToolAssistTest::speedOf));
    }

    @Test
    @DisplayName("只有背包条目 ⇒ 取最高（客户端 S2C 补的条目也走这条分层）")
    void containerOnly_takesBest() {
        ItemStack s2 = new ItemStack(Items.IRON_AXE);    s2.setDamageValue(2);
        ItemStack s7 = new ItemStack(Items.NETHERITE_AXE); s7.setDamageValue(7);

        float bonus = LivingToolAssist.assistBonus(
            List.of(inner(s2, 0), inner(s7, 1)), LivingToolAssistTest::speedOf);

        assertEquals(7.0F, bonus, 0.0001F);
    }
}
