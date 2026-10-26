package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 「满背包不丢物」守卫(2026-10-27 审查 P0)。
 *
 * <p><b>被守的行为</b>:原版 {@code Inventory#add} 的余量<b>留在传入栈里</b> ——
 * {@code handleWithdrawToInventory} 曾直接丢栈 ⇒ 配方书 Shift 取出活箱子物品、
 * 背包满时(恰是拿活箱子当仓库的典型场景)取出即<b>静默销毁</b>。
 * 修复后余量走 {@code Player#drop}(死亡掉落同款官方入口)掉在玩家脚边。</p>
 */
class ServerPacketHandlerInventorySafetyTest {

    /** 服务端玩家替身 —— 真实 Inventory + mock 玩家(掉落用 Mockito 验证)。 */
    private static ServerPlayer playerWith(Inventory inventory) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static Inventory fullInventory() {
        Inventory inv = new Inventory(mock(net.minecraft.world.entity.player.Player.class));
        for (int i = 0; i < inv.getContainerSize(); i++) {
            inv.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        return inv;
    }

    @Test
    @DisplayName("满背包 ⇒ 收不下的余量掉落,一格都不销毁")
    void fullInventory_remainderDropped() {
        Inventory inv = fullInventory();
        ServerPlayer player = playerWith(inv);

        ItemStack stack = new ItemStack(Items.IRON_INGOT, 64);
        ServerPacketHandler.addToInventoryOrDrop(player, stack);

        assertTrue(stack.isEmpty(), "余量必须全部有去向(掉落),不得留栈等 GC");
        for (int i = 0; i < inv.getContainerSize(); i++) {
            assertTrue(inv.getItem(i).is(Items.DIRT),
                "满背包的格子不许被覆盖(原来整格被成组的铁锭吃掉是另一类数据破坏)");
            assertEquals(64, inv.getItem(i).getCount());
        }
        verify(player).drop(org.mockito.ArgumentMatchers.argThat(
            s -> s.is(Items.IRON_INGOT) && s.getCount() == 64), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    @DisplayName("空背包 ⇒ 全部收下,不掉落")
    void emptyInventory_allStored() {
        Inventory inv = new Inventory(mock(net.minecraft.world.entity.player.Player.class));
        ServerPlayer player = playerWith(inv);

        ItemStack stack = new ItemStack(Items.IRON_INGOT, 64);
        ServerPacketHandler.addToInventoryOrDrop(player, stack);

        assertTrue(stack.isEmpty());
        assertEquals(64, inv.getItem(0).getCount(), "应落在第一格");
        org.mockito.Mockito.verify(player, org.mockito.Mockito.never())
            .drop(org.mockito.ArgumentMatchers.any(ItemStack.class),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("部分空间 ⇒ 收满剩余掉落(同合并余量)")
    void partialSpace_mergesThenDropsRemainder() {
        Inventory inv = new Inventory(mock(net.minecraft.world.entity.player.Player.class));
        inv.setItem(0, new ItemStack(Items.IRON_INGOT, 32));   // 只剩 32 格空间,且无空格
        for (int i = 1; i < inv.getContainerSize(); i++) {
            inv.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        ServerPlayer player = playerWith(inv);

        ItemStack stack = new ItemStack(Items.IRON_INGOT, 64);
        ServerPacketHandler.addToInventoryOrDrop(player, stack);

        assertEquals(64, inv.getItem(0).getCount(), "先合并到已有组");
        verify(player).drop(org.mockito.ArgumentMatchers.argThat(
            s -> s.is(Items.IRON_INGOT) && s.getCount() == 32), org.mockito.ArgumentMatchers.eq(false));
    }
}
