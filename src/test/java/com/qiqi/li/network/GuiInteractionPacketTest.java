package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * {@link GuiInteractionPacket} 目标槽判据的回归测试（2026-10-04）。
 *
 * <p><b>背景</b>：服务端 {@code resolveSlot} 原先只认「持活物品」的槽 ⇒ 活桶汲/倒的目标
 * 是<b>空槽</b>（倒水要求目标格无物品、汲水的源格也无物品）⇒ 汲/倒包一律被丢，
 * 表现为「活水桶右键没反应」。修复为「活物品 <b>或</b> 空槽」。</p>
 *
 * <p>空槽放行是安全的：非活桶动作只在客户端 {@code InteractionEntry.matchesTarget} 命中时才发包，
 * 而它对空槽恒 {@code false} ⇒ 空槽只会由活桶分支到达服务端。</p>
 */
class GuiInteractionPacketTest {

    @Test
    @DisplayName("空槽可解析 —— 活桶汲/倒的目标就是空槽（修复前为 false，此测试即回归守卫）")
    void emptySlot_isValid() {
        assertTrue(GuiInteractionPacket.isValidTarget(ItemStack.EMPTY),
            "空槽必须放行，否则活桶汲/倒的包会被丢弃");
    }

    @Test
    @DisplayName("持活物品可解析 —— 既有交互（点火 / 施肥）的目标")
    void livingItem_isValid() {
        ItemStack living = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(living, true);
        assertTrue(GuiInteractionPacket.isValidTarget(living));
    }

    @Test
    @DisplayName("非活物品不可解析 —— 不误伤原版放置/交换语义")
    void nonLivingItem_isInvalid() {
        assertFalse(GuiInteractionPacket.isValidTarget(new ItemStack(Items.STONE)));
    }
}
