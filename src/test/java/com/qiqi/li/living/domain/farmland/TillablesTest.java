package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.testutil.FakeHoe;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tillables 回归守卫 —— 锄头判定（只认 HOE_TILL）+ 可耕映射（对齐原版
 * {@code IBlockExtension#getToolModifiedState} 的 HOE_TILL 分支）。
 *
 * <p>重点钉住两条容易在重构中被「顺手改回去」的契约：
 *   1. 锄头判定不枚举物品清单，原版锄头与模组锄头走同一条 ItemAbility 路径；
 *   2. 砂土/缠根泥土的产物是泥土而非耕地（与原版一致），灰化土/菌丝不可耕。</p>
 */
class TillablesTest {

    /** 模组锄头替身：不继承 HoeItem，只声明 HOE_TILL */
    private static final Item MOD_HOE = FakeHoe.create();

    private static final List<Item> VANILLA_HOES = List.of(
        Items.WOODEN_HOE, Items.STONE_HOE, Items.GOLDEN_HOE,
        Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE);

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    // ---------- 锄头判定 ----------

    @Test
    @DisplayName("原版 6 种锄头均可执行 HOE_TILL（NeoForge patch 提供，无需枚举）")
    void vanillaHoesPerformHoeTill() {
        for (Item hoe : VANILLA_HOES) {
            assertTrue(Tillables.isHoeLike(new ItemStack(hoe)),
                "原版锄头应识别为锄头，实际未识别=" + hoe);
        }
    }

    @Test
    @DisplayName("模组锄头（自实现 Item，仅声明 HOE_TILL）同样识别")
    void modHoeIsRecognized() {
        assertTrue(Tillables.isHoeLike(new ItemStack(MOD_HOE)),
            "不继承 HoeItem 但声明 HOE_TILL 的模组锄头必须识别");
    }

    @Test
    @DisplayName("非锄头活物品与空栈不识别")
    void nonHoesAreRejected() {
        assertFalse(Tillables.isHoeLike(living(Items.DIAMOND_SWORD, 1)), "剑不是锄头");
        assertFalse(Tillables.isHoeLike(living(Items.BONE_MEAL, 1)), "骨粉不是锄头");
        assertFalse(Tillables.isHoeLike(ItemStack.EMPTY), "空栈不是锄头");
    }

    // ---------- 可耕映射 ----------

    @Test
    @DisplayName("映射对齐原版：泥土/草方块/土径 → 耕地")
    void tilledResultOfSoilBecomesFarmland() {
        assertEquals(Items.FARMLAND, Tillables.tilledResultOf(new ItemStack(Items.DIRT)));
        assertEquals(Items.FARMLAND, Tillables.tilledResultOf(new ItemStack(Items.GRASS_BLOCK)));
        assertEquals(Items.FARMLAND, Tillables.tilledResultOf(new ItemStack(Items.DIRT_PATH)));
    }

    @Test
    @DisplayName("映射对齐原版：砂土/缠根泥土 → 泥土（不是耕地）")
    void tilledResultOfCoarseBecomesDirt() {
        assertEquals(Items.DIRT, Tillables.tilledResultOf(new ItemStack(Items.COARSE_DIRT)),
            "砂土耕后是泥土，与原版一致");
        assertEquals(Items.DIRT, Tillables.tilledResultOf(new ItemStack(Items.ROOTED_DIRT)),
            "缠根泥土耕后是泥土，与原版一致");
    }

    @Test
    @DisplayName("非可耕物品（含灰化土/菌丝/石头/耕地）与空栈 → null")
    void nonTillableReturnsNull() {
        assertNull(Tillables.tilledResultOf(new ItemStack(Items.PODZOL)), "灰化土原版不可耕");
        assertNull(Tillables.tilledResultOf(new ItemStack(Items.MYCELIUM)), "菌丝原版不可耕");
        assertNull(Tillables.tilledResultOf(new ItemStack(Items.STONE)), "石头不可耕");
        assertNull(Tillables.tilledResultOf(new ItemStack(Items.FARMLAND)), "耕地不可再耕");
        assertNull(Tillables.tilledResultOf(ItemStack.EMPTY), "空栈不可耕");
    }

    // ---------- 组合门槛 ----------

    @Test
    @DisplayName("canTillWith 真值表：活锄头 × 活可耕物 才成立")
    void canTillWithTruthTable() {
        ItemStack livingDirt = living(Items.DIRT, 1);
        ItemStack livingHoe = living(Items.IRON_HOE, 1);
        ItemStack modHoe = living(MOD_HOE, 1);

        assertTrue(Tillables.canTillWith(livingHoe, livingDirt), "活原版锄头 + 活泥土");
        assertTrue(Tillables.canTillWith(modHoe, livingDirt), "活模组锄头 + 活泥土");
        assertTrue(Tillables.canTillWith(modHoe, living(Items.ROOTED_DIRT, 1)), "活模组锄头 + 活缠根泥土");

        assertFalse(Tillables.canTillWith(new ItemStack(Items.IRON_HOE), livingDirt),
            "非活锄头 → false（通配条目靠这里守活物品门槛）");
        assertFalse(Tillables.canTillWith(livingHoe, new ItemStack(Items.DIRT)),
            "非活目标 → false（否则会把普通土也耕了）");
        assertFalse(Tillables.canTillWith(living(Items.DIAMOND_SWORD, 1), livingDirt), "活剑不是锄头");
        assertFalse(Tillables.canTillWith(livingHoe, living(Items.STONE, 1)), "活石头不可耕");
        assertFalse(Tillables.canTillWith(ItemStack.EMPTY, livingDirt), "空手不触发");
    }

    // ---------- 注册目标集 ----------

    @Test
    @DisplayName("可耕目标集合 = 原版 5 种，不含灰化土/菌丝")
    void tillableTargetsMatchVanilla() {
        Set<Item> targets = Tillables.tillableTargets();
        assertTrue(targets.containsAll(List.of(
                Items.DIRT, Items.GRASS_BLOCK, Items.DIRT_PATH, Items.COARSE_DIRT, Items.ROOTED_DIRT)),
            "可耕目标应含原版全部 5 种，实际=" + targets);
        assertEquals(5, targets.size(), "可耕目标数量应恰为 5，实际=" + targets);
        assertFalse(targets.contains(Items.PODZOL), "灰化土不在可耕集合内");
        assertFalse(targets.contains(Items.MYCELIUM), "菌丝不在可耕集合内");
    }
}
