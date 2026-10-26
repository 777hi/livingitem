package com.qiqi.li.living.domain.redstone;

import static org.mockito.Mockito.mock;
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
 * 红石交互 handler 的<b>就地改写标脏</b>（2026-10-27 审查 P2，与 IgniteHandler P3-4 同类）：
 * {@code LivingXxxData.set} 是就地组件写，NBT 背书 handler 不触发保存钩子 ⇒
 * 不标脏则在「改完 → 下次因别的原因落盘」之间关服/崩服/卸块即丢状态。
 * 以拉杆为代表钉住（四个 handler 同构，逐一接线已在代码内注释）。
 */
class RedstoneHandlerSetChangedTest {

    @Test
    @DisplayName("拉杆切换成功 ⇒ targetSlot.setChanged 被调（否则开关态可因落盘缺失而丢失）")
    void leverToggle_marksSlotChanged() {
        ItemStack lever = new ItemStack(Items.LEVER);
        LivingItemManager.setLiving(lever, true);

        ServerPlayer player = mock(ServerPlayer.class);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        player.containerMenu = menu;   // containerMenu 是 public 字段（非方法），直接赋值
        Slot slot = mock(Slot.class);
        when(slot.getItem()).thenReturn(lever);

        new LeverToggleHandler().handle(player, slot);

        verify(slot).setChanged();
    }
}
