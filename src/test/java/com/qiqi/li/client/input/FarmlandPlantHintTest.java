package com.qiqi.li.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;

/**
 * 活耕地种植悬停提示守卫（2026-10-12）—— 光标持活种子悬停活耕地时的客户端反馈。
 *
 * <p><b>为什么需要</b>：原版 {@code AbstractContainerScreen#renderTooltip} 对「光标非空」
 * 的悬停不渲染任何 tooltip ⇒ 拿种子悬停耕地零反馈，右键没反应（种子不足 / 已种植）
 * 也无从判断。本提示补上这条信息。
 *
 * <p>守两条契约：
 *   1. **门槛与交互同源** —— 说「种得成」的条件与 {@code PlantCropHandler.canPlantWith}
 *      逐条一致（活种子 + 可种植 + 种子数 ≥ 耕地堆叠数），数量不足时提示差额；
 *   2. **文案分流** —— 充足 / 创造免费 / 不足 / 已种植四种态各自的键与参数。
 */
class FarmlandPlantHintTest {

    private static ItemStack farmland(int count) {
        ItemStack stack = new ItemStack(Items.FARMLAND, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack seeds(int count) {
        ItemStack stack = new ItemStack(Items.WHEAT_SEEDS, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static String keyOf(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents()).getKey();
    }

    private static Object[] argsOf(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents()).getArgs();
    }

    // ---------- 门槛（任一不过 → null，不渲染） ----------

    @Test
    @DisplayName("空光标 / 空槽位 → null")
    void emptyStacks_noHint() {
        assertNull(FarmlandPlantHint.tryBuildLines(ItemStack.EMPTY, farmland(1), false));
        assertNull(FarmlandPlantHint.tryBuildLines(seeds(1), ItemStack.EMPTY, false));
    }

    @Test
    @DisplayName("非活种子 / 非活耕地 → null（门槛与交互逐条对齐）")
    void notLivingOrNotFarmland_noHint() {
        assertNull(FarmlandPlantHint.tryBuildLines(new ItemStack(Items.WHEAT_SEEDS), farmland(1), false),
            "普通种子不是活种子");
        assertNull(FarmlandPlantHint.tryBuildLines(seeds(1), new ItemStack(Items.FARMLAND), false),
            "普通耕地不是活耕地");
    }

    @Test
    @DisplayName("活种子 + 活泥土（非 FARMLAND 物品）→ null")
    void livingDirtTarget_noHint() {
        ItemStack dirt = new ItemStack(Items.DIRT);
        LivingItemManager.setLiving(dirt, true);
        assertNull(FarmlandPlantHint.tryBuildLines(seeds(1), dirt, false),
            "活泥土不是耕地，种植交互不适用");
    }

    @Test
    @DisplayName("不可种植的活种子（活石头）→ null")
    void nonPlantableSeed_noHint() {
        ItemStack stone = new ItemStack(Items.STONE);
        LivingItemManager.setLiving(stone, true);
        assertNull(FarmlandPlantHint.tryBuildLines(stone, farmland(1), false),
            "石头不可种植");
    }

    // ---------- 文案分流 ----------

    @Test
    @DisplayName("种子充足（生存）：标题 + 作物 + 消耗，消耗数 = 耕地堆叠数")
    void sufficientSurvival_costEqualsFarmlandCount() {
        List<Component> lines = FarmlandPlantHint.tryBuildLines(seeds(16), farmland(4), false);

        assertEquals(3, lines.size());
        assertEquals("hint.livingitem.farmland.title", keyOf(lines.get(0)));
        assertEquals("hint.livingitem.farmland.crop", keyOf(lines.get(1)));
        assertEquals(1, argsOf(lines.get(1)).length, "作物行带 1 个参数（种子名）");
        assertInstanceOf(Component.class, argsOf(lines.get(1))[0]);
        assertEquals("hint.livingitem.farmland.cost", keyOf(lines.get(2)));
        assertEquals(4, argsOf(lines.get(2))[0], "消耗 = 耕地堆叠数（4），不是种子数");
    }

    @Test
    @DisplayName("种子充足（创造）：切换创造免费键（门槛不变，仅文案）")
    void sufficientCreative_freeKey() {
        List<Component> lines = FarmlandPlantHint.tryBuildLines(seeds(16), farmland(4), true);

        assertEquals(3, lines.size());
        assertEquals("hint.livingitem.farmland.cost_creative", keyOf(lines.get(2)));
        assertEquals(4, argsOf(lines.get(2))[0]);
    }

    @Test
    @DisplayName("种子不足：insufficient 行带（需要, 持有）差额 —— 原版零反馈的唯一信息源")
    void insufficient_showsDeficit() {
        List<Component> lines = FarmlandPlantHint.tryBuildLines(seeds(3), farmland(16), false);

        assertEquals(3, lines.size());
        assertEquals("hint.livingitem.farmland.title", keyOf(lines.get(0)));
        assertEquals("hint.livingitem.farmland.crop", keyOf(lines.get(1)));
        assertEquals("hint.livingitem.farmland.insufficient", keyOf(lines.get(2)));
        assertEquals(16, argsOf(lines.get(2))[0], "需要 = 耕地堆叠数");
        assertEquals(3, argsOf(lines.get(2))[1], "持有 = 光标种子数");
    }

    @Test
    @DisplayName("恰好相等（种子数 == 耕地堆叠数）算充足（canPlantWith 含等号）")
    void exactCount_sufficient() {
        List<Component> lines = FarmlandPlantHint.tryBuildLines(seeds(16), farmland(16), false);

        assertEquals(3, lines.size());
        assertEquals("hint.livingitem.farmland.cost", keyOf(lines.get(2)));
    }

    @Test
    @DisplayName("已种植：标题 + 已种植行（带作物名），交互此时也会拒绝重复种植")
    void planted_showsPlantedLine() {
        ItemStack farmland = farmland(4);
        FarmlandPlantComponent.set(farmland,
            FarmlandPlantComponent.DEFAULT.withCropSeed(Items.WHEAT_SEEDS, 7));

        List<Component> lines = FarmlandPlantHint.tryBuildLines(seeds(16), farmland, false);

        assertEquals(2, lines.size(), "已种植不给出消耗行");
        assertEquals("hint.livingitem.farmland.title", keyOf(lines.get(0)));
        assertEquals("hint.livingitem.farmland.planted", keyOf(lines.get(1)));
        assertEquals(1, argsOf(lines.get(1)).length);
        assertTrue(argsOf(lines.get(1))[0] instanceof Component, "参数是作物名组件");
    }

    @Test
    @DisplayName("活种子 + 活耕地（未种植）→ 有提示（正路径）")
    void happyPath_nonNull() {
        assertTrue(FarmlandPlantHint.tryBuildLines(seeds(1), farmland(1), false) != null);
    }
}
