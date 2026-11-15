package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 物品动作目标与所有动作共用的精确菜单槽身份。 */
class GuiInteractionPacketTest {

    @Test
    @DisplayName("空槽不是物品状态动作目标；容器动作须另行声明范围")
    void emptySlot_isInvalidItemTarget() {
        assertFalse(GuiInteractionPacket.isValidTarget(ItemStack.EMPTY));
    }

    @Test
    @DisplayName("非空活物品可作为物品状态动作目标")
    void livingItem_isValid() {
        ItemStack living = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(living, true);
        assertTrue(GuiInteractionPacket.isValidTarget(living));
    }

    @Test
    @DisplayName("非活物品不能作为物品状态动作目标")
    void nonLivingItem_isInvalid() {
        assertFalse(GuiInteractionPacket.isValidTarget(new ItemStack(Items.STONE)));
    }

    @Test
    @DisplayName("双索引不符时拒绝；所有动作均不回退到另一物理同号槽")
    void mismatchedPhysicalIndex_neverFallsBack() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));
        menu.attach(slot(13));

        assertNull(GuiInteractionPacket.resolveSlot(menu, 0, 13));
        assertSame(menu.getSlot(1), GuiInteractionPacket.resolveSlot(menu, 1, 13));
    }

    @Test
    @DisplayName("同物理编号的不同库存按精确菜单索引选择，不比较货物内容")
    void duplicatePhysicalIndex_selectsExactMenuEntry() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));
        menu.attach(slot(0));

        assertSame(menu.getSlot(0), GuiInteractionPacket.resolveSlot(menu, 0, 0));
        assertSame(menu.getSlot(1), GuiInteractionPacket.resolveSlot(menu, 1, 0));
    }

    @Test
    @DisplayName("越界菜单索引与负物理编号拒绝，不能按物理编号找回目标")
    void invalidIndices_areRejected() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));

        assertNull(GuiInteractionPacket.resolveSlot(menu, -1, 0));
        assertNull(GuiInteractionPacket.resolveSlot(menu, 1, 0));
        assertNull(GuiInteractionPacket.resolveSlot(menu, Integer.MAX_VALUE, 0));
        assertNull(GuiInteractionPacket.resolveSlot(menu, 0, -1));
    }

    @Test
    @DisplayName("非 active 槽即使双索引匹配也拒绝")
    void inactiveSlot_isRejected() {
        FakeMenu menu = new FakeMenu();
        menu.attach(new Slot(new SimpleContainer(9), 0, 0, 0) {
            @Override public boolean isActive() { return false; }
        });
        menu.attach(slot(0));

        assertNull(GuiInteractionPacket.resolveSlot(menu, 0, 0));
        assertSame(menu.getSlot(1), GuiInteractionPacket.resolveSlot(menu, 1, 0));
    }

    private static Slot slot(int physical) {
        return new Slot(new SimpleContainer(41), physical, 0, 0);
    }

    private static class FakeMenu extends AbstractContainerMenu {
        FakeMenu() { super(null, 0); }
        void attach(Slot slot) { addSlot(slot); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
