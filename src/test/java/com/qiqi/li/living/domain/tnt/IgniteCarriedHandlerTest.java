package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * 光标持活 TNT → 右键槽内活打火石（{@link IgniteCarriedHandler}，此前零测试）。
 *
 * <p>与 {@link IgniteHandler} 对称：一个点「槽里的 TNT」，一个点「光标上的 TNT」。
 * 拒绝分支用 {@code broadcastChanges} 未被调用来兜住（否则「没抛异常」这种断言等于没测）。
 * 生存成功路径要求真实扣耐久（mock 下不稳定），由同构的 `IgniteHandler` 覆盖成功路径。</p>
 */
class IgniteCarriedHandlerTest {

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static ItemStack livingTnt() {
        return living(new ItemStack(Items.TNT));
    }

    private static ItemStack livingFlint() {
        return living(new ItemStack(Items.FLINT_AND_STEEL));
    }

    private static AbstractContainerMenu menuWith(ItemStack carried) {
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        when(menu.getCarried()).thenReturn(carried);
        return menu;
    }

    private static ServerPlayer playerWith(AbstractContainerMenu menu, boolean creative) {
        ServerPlayer player = mock(ServerPlayer.class);
        player.containerMenu = menu;           // Player.containerMenu 是 public 字段
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
    @DisplayName("创造 + 光标活 TNT ⇒ 点燃光标上的 TNT 并广播")
    void creative_carriedLivingTnt_ignites() {
        ItemStack carried = livingTnt();
        AbstractContainerMenu menu = menuWith(carried);

        new IgniteCarriedHandler().handle(playerWith(menu, true), slotWith(livingFlint()));

        assertTrue(ignited(carried));
        verify(menu).broadcastChanges();
    }

    @Test
    @DisplayName("创造 + 光标为空 ⇒ 拒绝（不广播）")
    void creative_emptyCarried_rejects() {
        AbstractContainerMenu menu = menuWith(ItemStack.EMPTY);

        new IgniteCarriedHandler().handle(playerWith(menu, true), slotWith(livingFlint()));

        verify(menu, never()).broadcastChanges();
    }

    @Test
    @DisplayName("创造 + 光标是未活化 TNT ⇒ 拒绝")
    void creative_plainTnt_rejects() {
        ItemStack carried = new ItemStack(Items.TNT);
        AbstractContainerMenu menu = menuWith(carried);

        new IgniteCarriedHandler().handle(playerWith(menu, true), slotWith(livingFlint()));

        assertFalse(ignited(carried), "普通 TNT 不该被点燃");
        verify(menu, never()).broadcastChanges();
    }

    @Test
    @DisplayName("生存 + 槽内是未活化打火石 ⇒ 拒绝")
    void survival_plainFlint_rejects() {
        ItemStack carried = livingTnt();
        AbstractContainerMenu menu = menuWith(carried);

        new IgniteCarriedHandler().handle(
            playerWith(menu, false), slotWith(new ItemStack(Items.FLINT_AND_STEEL)));

        assertFalse(ignited(carried));
        verify(menu, never()).broadcastChanges();
    }

    @Test
    @DisplayName("生存 + 光标为空 ⇒ 拒绝")
    void survival_emptyCarried_rejects() {
        AbstractContainerMenu menu = menuWith(ItemStack.EMPTY);

        new IgniteCarriedHandler().handle(playerWith(menu, false), slotWith(livingFlint()));

        verify(menu, never()).broadcastChanges();
    }
}
