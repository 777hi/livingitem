package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * 物品形态容器（活背包）的槽位直发通道（2026-10-31，活水车转向不刷新的根因修复）。
 *
 * <p>修前：{@code syncSlotToClients} 对物品形态是 no-op（无世界位置 ⇒ 早退），而
 * 兜底的原版 {@code broadcastChanges} 用 {@code ItemStack.matches} 比对菜单槽快照 ——
 * 快照是 {@code stack.copy()}，patch map copy-on-write 共享 ⇒ 就地改组件连快照一起改掉
 * ⇒ 恒等 ⇒ 纯组件翻转（水车转向 / 熔炉燃烧态）在打开的背包 GUI 里永不刷新，
 * 只有重开 GUI 的全量下发才恢复。</p>
 *
 * <p>修后：空位置 + 有容器 UUID ⇒ 按容器 UUID 找观众（ContainerViewers 的 ITEM_FORM 分支），
 * 槽位按 <b>handler 实例 + 物理槽号</b> 匹配后直发槽位包。本测试钉住这条通道的四条行为。</p>
 */
class ItemFormSlotSyncTest {

    private static final int MENU_ID = 7;

    private ServerLevel level;
    private ItemStackHandler handler;
    private ItemStackHandler decoyHandler;
    private UUID containerUuid;
    private TestMenu viewerMenu;
    private ServerPlayer viewer;
    private ServerPlayer stranger;
    private ServerPlayer closedPlayer;

    /** 真实菜单替身：真实 slots / remoteSlots / stateId，槽位由测试按需挂。 */
    private static final class TestMenu extends AbstractContainerMenu {
        TestMenu(int id) {
            super(null, id);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        void add(IItemHandler h, int index) {
            addSlot(new SlotItemHandler(h, index, index * 18, 0));
        }
    }

    @BeforeEach
    void setUp() {
        ItemFormContainers.clearForTest();

        level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(playerList);

        handler = new ItemStackHandler(9);
        decoyHandler = new ItemStackHandler(9);
        containerUuid = UUID.randomUUID();

        // 观众的菜单：槽 0 = 本 handler 的物理槽 0；槽 1 = 另一个 handler 的【同号】槽
        //（升级槽替身 —— 只按槽号匹配会把数据推进升级槽，按 handler 实例匹配才不会撞）
        viewerMenu = new TestMenu(MENU_ID);
        viewerMenu.add(handler, 0);
        viewerMenu.add(decoyHandler, 0);

        viewer = player();
        viewer.containerMenu = viewerMenu;
        stranger = player();
        stranger.containerMenu = new TestMenu(MENU_ID + 1);
        closedPlayer = player();   // containerMenu 恒为 inventoryMenu（GUI 关着）

        when(playerList.getPlayers()).thenReturn(List.of(viewer, stranger, closedPlayer));

        // 只认观众菜单的假来源 —— ContainerViewers 的 ITEM_FORM 分支靠它认「谁正开着这个容器」
        ItemFormContainers.register(new ItemFormContainers.Source() {
            @Override public boolean isContainerItem(ItemStack stack) { return false; }
            @Override public IItemHandler contentHandler(ItemStack stack) { return handler; }
            @Override public ItemFormContainers.Handle handleOfMenu(
                    AbstractContainerMenu menu, Player player) {
                return menu == viewerMenu
                    ? new ItemFormContainers.Handle(handler, containerUuid, 3) : null;
            }
        });
    }

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
    }

    private ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.isSpectator()).thenReturn(true);
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        return player;
    }

    private ItemFormContainerContext context() {
        return new ItemFormContainerContext(handler, containerUuid, level, 3, null);
    }

    /** 走生产的延迟路径：tick 中标脏 → tick 末 flushDirtySlots。 */
    private void syncDirtySlot(ItemFormContainerContext ctx, int logicalSlot, ItemStack stack) {
        TickContext tick = new TickContext(ctx);
        ctx.setTickContext(tick);
        ctx.syncSlotToClients(logicalSlot, stack);
        ctx.flushDirtySlots();
        ctx.setTickContext(null);
    }

    private static ClientboundContainerSetSlotPacket soleSlotPacket(ServerPlayer player) {
        ArgumentCaptor<Packet<?>> captor = ArgumentCaptor.forClass(Packet.class);
        verify(player.connection, times(1)).send(captor.capture());
        Packet<?> sent = captor.getValue();
        assertTrue(sent instanceof ClientboundContainerSetSlotPacket,
            "应发槽位包，实际是 " + sent.getClass().getSimpleName());
        return (ClientboundContainerSetSlotPacket) sent;
    }

    @Test
    @DisplayName("脏槽刷新：发给开着这个背包 GUI 的观众，槽号用菜单槽索引")
    void dirtySlot_pushedToViewer() {
        ItemStack updated = new ItemStack(Items.DIAMOND, 3);
        handler.setStackInSlot(0, updated);

        syncDirtySlot(context(), 0, updated);

        ClientboundContainerSetSlotPacket packet = soleSlotPacket(viewer);
        assertEquals(MENU_ID, packet.getContainerId());
        assertEquals(0, packet.getSlot(), "handler 槽 0 在菜单里就是槽 0");
        assertTrue(ItemStack.isSameItemSameComponents(packet.getItem(), updated));

        // 观众菜单的快照也要跟上 —— 否则下一拍原版比对还会误判
        assertTrue(ItemStack.isSameItemSameComponents(viewerMenu.remoteSlots.get(0), updated));
        verifyNoInteractions(stranger.connection, closedPlayer.connection);
    }

    @Test
    @DisplayName("同号槽挂在别的 handler 上（升级槽替身）⇒ 不收数据，只发本 handler 的槽")
    void sameIndexOtherHandler_notPushed() {
        ItemStack updated = new ItemStack(Items.GOLD_INGOT, 1);
        decoyHandler.setStackInSlot(0, new ItemStack(Items.STONE));
        handler.setStackInSlot(0, updated);

        syncDirtySlot(context(), 0, updated);

        ClientboundContainerSetSlotPacket packet = soleSlotPacket(viewer);
        assertEquals(0, packet.getSlot(), "只发本 handler 的槽；升级槽替身不冒领");
        assertTrue(packet.getItem().is(Items.GOLD_INGOT), "发的是本槽内容，不是同号槽的");
    }

    @Test
    @DisplayName("开着别的背包 GUI / GUI 关着 ⇒ 都不收包")
    void otherOrClosedGui_noPacket() {
        ItemStack updated = new ItemStack(Items.IRON_INGOT, 2);
        handler.setStackInSlot(0, updated);

        syncDirtySlot(context(), 0, updated);

        verify(stranger.connection, never()).send(any(Packet.class));
        verify(closedPlayer.connection, never()).send(any(Packet.class));
    }

    @Test
    @DisplayName("无观众（没人开 GUI）⇒ 不发包，不抛")
    void noViewer_noPacket() {
        when(level.getServer().getPlayerList().getPlayers()).thenReturn(List.of(closedPlayer));

        ItemStack updated = new ItemStack(Items.EMERALD, 1);
        handler.setStackInSlot(0, updated);

        syncDirtySlot(context(), 0, updated);

        verifyNoInteractions(closedPlayer.connection);
    }
}
