package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活打火石 → 活 TNT 的点燃交互（{@link IgniteHandler}，此前零测试）。
 *
 * <p>2026-10-28 补测试时抓到一个真问题：**生存模式光标为空也会点燃** ——
 * 原校验只拦「光标有但不是活打火石」，空光标一路放行 ⇒ 伪造包可空手点燃任意活 TNT。
 * 修复口径：生存必须持有非空活打火石；创造模式仍信任客户端（背包页 carried 是虚拟的）。</p>
 */
class TntIgniteHandlerTest {

    private static ItemStack tnt() {
        return living(new ItemStack(Items.TNT));
    }

    private static ItemStack livingFlint() {
        return living(new ItemStack(Items.FLINT_AND_STEEL));
    }

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ServerPlayer playerWith(ItemStack carried, boolean creative) {
        ServerPlayer player = mock(ServerPlayer.class);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        when(menu.getCarried()).thenReturn(carried);
        player.containerMenu = menu;          // Player.containerMenu 是 public 字段
        when(player.isCreative()).thenReturn(creative);
        return player;
    }

    private static Slot slotWith(ItemStack stack) {
        Slot slot = mock(Slot.class);
        when(slot.getItem()).thenReturn(stack);
        return slot;
    }

    private static boolean ignited(ItemStack stack) {
        return LivingTntData.of(stack).explosion().ignited();
    }

    @Test
    @DisplayName("创造 + 空光标 ⇒ 点燃（背包页 carried 客户端虚拟，信任客户端）")
    void creative_emptyCarried_ignites() {
        ItemStack target = tnt();

        new IgniteHandler().handle(playerWith(ItemStack.EMPTY, true), slotWith(target));

        assertTrue(ignited(target));
    }

    @Test
    @DisplayName("创造 + 光标是活打火石 ⇒ 点燃")
    void creative_livingFlint_ignites() {
        ItemStack target = tnt();

        new IgniteHandler().handle(playerWith(livingFlint(), true), slotWith(target));

        assertTrue(ignited(target));
    }

    @Test
    @DisplayName("创造 + 光标是普通物品 ⇒ 拒绝")
    void creative_wrongCarried_rejects() {
        ItemStack target = tnt();

        new IgniteHandler().handle(playerWith(new ItemStack(Items.STONE), true), slotWith(target));

        assertFalse(ignited(target));
    }

    @Test
    @DisplayName("生存 + 空光标 ⇒ 拒绝（伪造包不得空手点燃，2026-10-28 修）")
    void survival_emptyCarried_rejects() {
        ItemStack target = tnt();

        new IgniteHandler().handle(playerWith(ItemStack.EMPTY, false), slotWith(target));

        assertFalse(ignited(target), "生存模式空光标必须拒绝");
    }

    @Test
    @DisplayName("生存 + 普通打火石（未活化）⇒ 拒绝")
    void survival_plainFlint_rejects() {
        ItemStack target = tnt();

        new IgniteHandler().handle(playerWith(new ItemStack(Items.FLINT_AND_STEEL), false), slotWith(target));

        assertFalse(ignited(target));
    }
}
