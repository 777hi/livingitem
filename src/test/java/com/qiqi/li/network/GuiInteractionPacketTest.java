package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // ── 槽位定位（创造模式索引错位，2026-10-06）──────────────────

    /** 最小菜单：只为装槽位（判定逻辑不需要其它行为）。 */
    private static class FakeMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
        FakeMenu() { super(null, 0); }
        void attach(net.minecraft.world.inventory.Slot s) { super.addSlot(s); }
        @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
            return ItemStack.EMPTY;
        }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
    }

    private static net.minecraft.world.inventory.Slot slot(int containerSlot) {
        return new net.minecraft.world.inventory.Slot(new net.minecraft.world.SimpleContainer(9), containerSlot, 0, 0);
    }

    @Test
    @DisplayName("创造模式索引错位：索引直查被否决 ⇒ 回退按 containerSlot 精确定位（倒水静默失败的根因）")
    void resolveSlot_fallsBackToContainerSlotWhenIndexIsWrong() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));     // 菜单索引 0 ← 创造模式客户端 Index 0 对应的是无关槽（如合成结果槽）
        menu.attach(slot(13));    // 真实目标：容器槽位 13（菜单索引 1）

        // 谓词模拟「索引 0 那个槽解析不出活容器」⇒ 被否决
        var onlyContainerSlot13 = (java.util.function.Predicate<net.minecraft.world.inventory.Slot>)
            s -> s.getContainerSlot() == 13;

        var resolved = GuiInteractionPacket.resolveSlot(menu, 0, 13, onlyContainerSlot13);
        assertNotNull(resolved, "索引直查被否决后，应按 containerSlot 回退命中");
        assertEquals(13, resolved.getContainerSlot(), "必须落在真实容器槽位 13 上，而不是索引 0");
    }

    @Test
    @DisplayName("索引优先语义保留：同一菜单下（如箱子界面）索引直查命中就用它")
    void resolveSlot_prefersSlotIndexWhenAcceptable() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(9));
        menu.attach(slot(9));   // 两个槽的 containerSlot 相同（箱子槽 9 与背包槽 9 的经典歧义）

        var acceptBoth = (java.util.function.Predicate<net.minecraft.world.inventory.Slot>) s -> true;
        var resolved = GuiInteractionPacket.resolveSlot(menu, 1, 9, acceptBoth);
        assertNotNull(resolved);
        assertEquals(menu.getSlot(1), resolved, "索引直查可接受时不应改道（避免同类槽位歧义）");
    }
}
