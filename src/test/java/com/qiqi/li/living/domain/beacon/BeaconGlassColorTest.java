package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 容器玻璃取色与同列扫描测试（设计稿 §2.2 U3-A 容器侧延伸）。
 */
class BeaconGlassColorTest {

    @Test
    @DisplayName("16 色染色玻璃 ⇒ DyeColor.getTextureDiffuseColor（与 NeoForge 扩展点同口径）")
    void stainedGlass() {
        assertEquals(DyeColor.RED.getTextureDiffuseColor(),
            BeaconGlassColor.of(new ItemStack(Items.RED_STAINED_GLASS)));
        assertEquals(DyeColor.WHITE.getTextureDiffuseColor(),
            BeaconGlassColor.of(new ItemStack(Items.WHITE_STAINED_GLASS)));
        assertEquals(DyeColor.LIGHT_BLUE.getTextureDiffuseColor(),
            BeaconGlassColor.of(new ItemStack(Items.LIGHT_BLUE_STAINED_GLASS)));
        assertEquals(DyeColor.BLACK.getTextureDiffuseColor(),
            BeaconGlassColor.of(new ItemStack(Items.BLACK_STAINED_GLASS)));
    }

    @Test
    @DisplayName("染色玻璃板同样取色（原版 BeaconBeamBlock 方块集）")
    void stainedGlassPane() {
        assertEquals(DyeColor.CYAN.getTextureDiffuseColor(),
            BeaconGlassColor.of(new ItemStack(Items.CYAN_STAINED_GLASS_PANE)));
        assertEquals(DyeColor.PINK.getTextureDiffuseColor(),
            BeaconGlassColor.of(new ItemStack(Items.PINK_STAINED_GLASS_PANE)));
    }

    @Test
    @DisplayName("普通玻璃 / 非玻璃物品 / 空栈 ⇒ 无色")
    void nonStained() {
        assertNull(BeaconGlassColor.of(new ItemStack(Items.GLASS)));
        assertNull(BeaconGlassColor.of(new ItemStack(Items.GLASS_PANE)));
        assertNull(BeaconGlassColor.of(new ItemStack(Items.STONE)));
        assertNull(BeaconGlassColor.of(ItemStack.EMPTY));
        assertNull(BeaconGlassColor.of(null));
    }

    @Test
    @DisplayName("同列扫描：自下而上收集玻璃色，非玻璃跳过不记，越界即停")
    void scanColumn() {
        // 27 槽箱子（3 行 × 9 列）：信标在行 1 中列（槽 13）⇒ 上方 = 行 0 同列（槽 4），再上越界
        ItemStack[] slots = new ItemStack[27];
        slots[13] = new ItemStack(Items.BEACON);
        slots[4] = new ItemStack(Items.RED_STAINED_GLASS);
        List<Integer> colors = BeaconGlassColor.scanColumn(13, 27, 9, i -> slots[i]);
        assertEquals(List.of(DyeColor.RED.getTextureDiffuseColor()), colors);
    }

    @Test
    @DisplayName("同列扫描：多层玻璃按自下而上顺序收集（等价原版可叠放玻璃）")
    void scanColumnStacked() {
        ItemStack[] slots = new ItemStack[27];
        slots[13] = new ItemStack(Items.BEACON);          // 行 1 中列
        slots[4] = new ItemStack(Items.RED_STAINED_GLASS);    // 行 0 中列（紧贴上方）
        slots[22] = new ItemStack(Items.STONE);               // 行 2 同列（下方，无关）
        slots[14] = new ItemStack(Items.BLUE_STAINED_GLASS);  // 行 1 右一列（不同列，无关）

        List<Integer> colors = BeaconGlassColor.scanColumn(13, 27, 9, i -> slots[i]);
        assertEquals(List.of(
            DyeColor.RED.getTextureDiffuseColor()), colors);   // 只有紧贴上方那格（再上越界）
    }

    @Test
    @DisplayName("同列扫描：非玻璃物品不遮挡（跳过继续），空格子也跳过")
    void scanColumnSkipsNonGlass() {
        ItemStack[] slots = new ItemStack[27];
        slots[13] = new ItemStack(Items.BEACON);          // 行 1 中列
        slots[4] = new ItemStack(Items.STONE);            // 行 0 中列 = 非玻璃 ⇒ 不记不挡
        List<Integer> colors = BeaconGlassColor.scanColumn(13, 27, 9, i -> slots[i]);
        assertTrue(colors.isEmpty());                     // 上方只有一格且非玻璃 ⇒ 空
    }

    @Test
    @DisplayName("同列扫描：信标在顶行 ⇒ 上方越界 ⇒ 空")
    void scanColumnTopRow() {
        ItemStack[] slots = new ItemStack[27];
        slots[4] = new ItemStack(Items.BEACON);           // 行 0 中列（顶行）
        List<Integer> colors = BeaconGlassColor.scanColumn(4, 27, 9, i -> slots[i]);
        assertTrue(colors.isEmpty());
    }
}
