package com.qiqi.li.client.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import com.qiqi.li.living.api.LivingItemManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活箱子「Shift+左键 存放」判定（批次 3，2026-11-01）。
 *
 * <p>守的是「<b>一次手势只产生一个效果</b>」：这条判据原先在两个 mixin 里各写一份，
 * 连「NBT 序列化 + 发包」那三行都是复制的 ⇒ 两份会各自漂移，且调用方漏一个 {@code return}
 * 就会与「手持触发型交互」叠加。判据收进 {@link LivingChestDepositHelper} 后，
 * 本测试覆盖它的每一条早退。</p>
 *
 * <p>发包（{@code sendDeposit}）不在此测 —— 它需要一个真实客户端玩家与网络通道，
 * 由实机清单 M-1/M-2 覆盖（见整改计划 §批次 3.4）。</p>
 */
class LivingChestDepositHelperTest {

    // 用常量本身，不写数字：GLFW 的鼠标键是 0 基的（MOUSE_BUTTON_1 == 0），
    // 第一版这里写 1/2 ⇒ 正例直接假失败（2026-11-01 实测）。
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_1;
    private static final int RIGHT = GLFW.GLFW_MOUSE_BUTTON_2;

    private static Slot slotWith(ItemStack stack) {
        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, stack);
        return new Slot(container, 0, 0, 0);
    }

    private static ItemStack livingChest() {
        ItemStack chest = new ItemStack(Items.CHEST);
        LivingItemManager.setLiving(chest, true);
        return chest;
    }

    @Test
    @DisplayName("正常路径：普通物品 + Shift + 左键 + 页签激活 + 书面板可见 ⇒ 存放")
    void normalItem_deposits() {
        assertTrue(LivingChestDepositHelper.shouldDeposit(
            slotWith(new ItemStack(Items.IRON_INGOT)), LEFT, true, true, true));
    }

    @Test
    @DisplayName("空槽不存放")
    void emptySlot_neverDeposits() {
        Slot empty = slotWith(ItemStack.EMPTY);
        assertFalse(LivingChestDepositHelper.shouldDeposit(empty, LEFT, true, true, true));
    }

    @Test
    @DisplayName("槽内是活箱子不存放 —— 它是容器，不是待收纳的内容物")
    void livingChestSlot_isNotContent() {
        assertFalse(LivingChestDepositHelper.shouldDeposit(
            slotWith(livingChest()), LEFT, true, true, true));
    }

    @Test
    @DisplayName("页签未激活 ⇒ 不存放（标签状态是唯一的认领凭据）")
    void inactiveTab_neverDeposits() {
        assertFalse(LivingChestDepositHelper.shouldDeposit(
            slotWith(new ItemStack(Items.IRON_INGOT)), LEFT, true, false, true));
    }

    @Test
    @DisplayName("配方书面板不可见 ⇒ 不存放（此时标签根本没画，不能认领这块屏幕区域）")
    void hiddenBook_neverDeposits() {
        assertFalse(LivingChestDepositHelper.shouldDeposit(
            slotWith(new ItemStack(Items.IRON_INGOT)), LEFT, true, true, false));
    }

    @Test
    @DisplayName("没按 Shift / 不是左键 ⇒ 不存放（原版行为照常）")
    void wrongGesture_neverDeposits() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        assertFalse(LivingChestDepositHelper.shouldDeposit(
            slotWith(iron), LEFT, false, true, true), "未按 Shift");
        assertFalse(LivingChestDepositHelper.shouldDeposit(
            slotWith(iron), RIGHT, true, true, true), "右键");
    }

    @Test
    @DisplayName("槽位为 null ⇒ 不存放且不抛（空手悬在 GUI 背景上）")
    void nullSlot_isSafe() {
        assertFalse(LivingChestDepositHelper.shouldDeposit(null, LEFT, true, true, true));
    }
}
