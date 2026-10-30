package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.container.MenuSlotOwners;
import com.qiqi.li.living.container.TickableContainerContext;

/** 流体快照的清空边沿、真实单箱每拍发包与关闭/重开生命周期守卫。 */
class FluidFlowServerSyncTest {

    private static final BlockPos CHEST_POS = new BlockPos(4, 64, 4);
    private static final int SOURCE_SLOT = 0;
    private FluidFlowBehavior previousWaterBehavior;

    @BeforeEach
    void setUp() {
        FluidFlowServerSync.clearForTest();
        ContainerLivingItemHandler.clearAllCaches();
        ItemFormContainers.clearForTest();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        FluidFlowServerSync.clearForTest();
        ContainerLivingItemHandler.clearAllCaches();
        ItemFormContainers.clearForTest();
        if (previousWaterBehavior != null) {
            FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), previousWaterBehavior);
        }
        // 菜单解析只写「按类去重日志」缓存；清理入口是包级可见。
        var clearMenuCache = MenuSlotOwners.class.getDeclaredMethod("clearCacheForTest");
        clearMenuCache.setAccessible(true);
        clearMenuCache.invoke(null);
    }

    @Test
    @DisplayName("边沿：有数据期间不下发清空；数据清空后恰好下发一次")
    void edge_fireOnceOnDataClear() {
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("k", true), "首次有数据：不需要清空");
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("k", true), "持续有数据：不需要清空");

        assertTrue(FluidFlowServerSync.markActiveAndCheckClear("k", false),
            "数据清空且曾下发过 ⇒ 必须下发一次清空包");

        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("k", false),
            "清空包只发一次（边沿，非电平）");
    }

    @Test
    @DisplayName("从未有数据的容器：任何时刻都不下发清空（无流体容器零开销）")
    void neverActive_neverClears() {
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("cold", false));
        assertFalse(FluidFlowServerSync.markActiveAndCheckClear("cold", false));
    }

    @Test
    @DisplayName("重新有数据后再次清空：边沿重新触发（反复汲/倒场景）")
    void edge_rearmsAfterDataReturns() {
        FluidFlowServerSync.markActiveAndCheckClear("k", true);
        assertTrue(FluidFlowServerSync.markActiveAndCheckClear("k", false), "第一次清空触发");

        FluidFlowServerSync.markActiveAndCheckClear("k", true);   // 又倒了一个源
        assertTrue(FluidFlowServerSync.markActiveAndCheckClear("k", false), "再次清空再次触发");
    }

    @Test
    @DisplayName("真实单箱：稳定非空流体连续两拍都实际发包，只发给本箱查看者")
    void realChest_unchangedFlows_sendOnEveryTick() {
        SingleChestFixture fixture = singleChest();

        fixture.tickAndFlush();
        assertWaterSnapshot(fixture.sentPackets(1).getFirst(), fixture.context.getCacheKey());

        fixture.tickAndFlush();
        List<FluidFlowSyncPacket> packets = fixture.sentPackets(2);
        for (FluidFlowSyncPacket packet : packets) {
            assertWaterSnapshot(packet, fixture.context.getCacheKey());
        }
    }

    @Test
    @DisplayName("真实单箱：关闭发空包，同坐标新 BE/新菜单重开后旧活跃状态不阻挡非空包")
    void realChest_closeThenReloadAtSamePosition_sendsFreshSnapshot() {
        SingleChestFixture fixture = singleChest();
        String key = fixture.context.getCacheKey();
        ChestBlockEntity oldChest = fixture.chest;
        ChestMenu oldMenu = (ChestMenu) fixture.viewer.containerMenu;
        ContainerFluidData oldData = fixture.fluidData;
        fixture.tickAndFlush();
        assertWaterSnapshot(fixture.sentPackets(1).getFirst(), key);

        FluidFlowServerSync.onContainerClose(new PlayerContainerEvent.Close(fixture.viewer, oldMenu));
        fixture.stopViewing();
        FluidFlowSyncPacket clear = fixture.sentPackets(2).get(1);
        assertChestTarget(clear, key);
        assertTrue(clear.cells().isEmpty(), "关闭后该连接的最后一包必须清空水层");
        assertTrue(clear.palette().isEmpty());
        assertFalse(oldData.isEmpty(), "关闭只清客户端快照，不能清服务端的源");

        // 重载只保留源附件，不保留流动表；CLIENT_ACTIVE 不随容器运行时数据一起清理。
        ContainerFluidData savedSources = new ContainerFluidData();
        oldData.getGeneratedSources().forEach(savedSources::registerGeneratedSource);
        ContainerLivingItemHandler.removeDataByPos(fixture.level, CHEST_POS);
        ChestBlockEntity reloadedChest = fixture.chestAt(CHEST_POS);
        reloadedChest.setData(WaterComponents.CONTAINER_FLUID_DATA.value(), savedSources);
        ChestMenu reopenedMenu = fixture.openChest(reloadedChest, 2);

        assertNotSame(oldChest, fixture.chest);
        assertNotSame(oldMenu, reopenedMenu);
        assertNotSame(oldData, fixture.fluidData);
        assertEquals(key, fixture.context.getCacheKey(), "同维度同坐标重开必须沿用原缓存键");
        assertTrue(fixture.fluidData.getFlows().isEmpty(), "新 BE 从源附件回填，流动表由下一拍重建");

        fixture.tickAndFlush();
        assertWaterSnapshot(fixture.sentPackets(3).get(2), key);
    }

    @Test
    @DisplayName("真实单箱：无人查看多拍不发包，重开第一拍即可发出非空快照")
    void realChest_ticksWithoutViewers_sendWhenReopened() {
        SingleChestFixture fixture = singleChest();
        fixture.stopViewing();
        for (int tick = 0; tick < 20; tick++) {
            fixture.tickAndFlush();
        }
        assertFalse(fixture.fluidData.getFlows().isEmpty(), "无人查看期间服务端仍有真实水流");
        verifyNoInteractions(fixture.viewer.connection,
            fixture.otherChestViewer.connection, fixture.idlePlayer.connection);

        fixture.openChest(fixture.chest, 2);
        fixture.tickAndFlush();

        assertWaterSnapshot(fixture.sentPackets(1).getFirst(), fixture.context.getCacheKey());
    }

    private SingleChestFixture singleChest() {
        previousWaterBehavior = FluidFlowBehaviors.of(Fluids.WATER.getFluidType());
        // 一拍形成稳定的源和相邻流动格；恢复原行为在 AfterEach 中执行。
        FluidFlowBehaviors.register(Fluids.WATER.getFluidType(), FluidFlowBehavior.flowing(1, 0));
        return new SingleChestFixture();
    }

    private static void assertChestTarget(FluidFlowSyncPacket packet, String key) {
        assertEquals(key, packet.containerKey());
        assertEquals(9, packet.width());
        assertEquals(FluidFlowClientCache.RenderTarget.BLOCK, packet.target());
    }

    private static void assertWaterSnapshot(FluidFlowSyncPacket packet, String key) {
        assertChestTarget(packet, key);
        assertEquals(List.of("minecraft:water"), packet.palette());
        assertEquals(Set.of(SOURCE_SLOT, 1, 9), packet.cells().keySet(),
            "同一份非空快照包含源与真实流动格，不能只靠发包次数通过");
        assertArrayEquals(new int[]{0, -1, 0}, packet.cells().get(SOURCE_SLOT));
        assertArrayEquals(new int[]{1, SOURCE_SLOT, 0}, packet.cells().get(1));
        assertArrayEquals(new int[]{1, SOURCE_SLOT, 0}, packet.cells().get(9));
    }

    /** 真实单箱菜单与槽位；仅世界、在线玩家列表和玩家连接使用边界桩。 */
    private static final class SingleChestFixture {
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer viewer;
        final ServerPlayer otherChestViewer;
        final ServerPlayer idlePlayer;
        ChestBlockEntity chest;
        TickableContainerContext context;
        ContainerFluidData fluidData;

        SingleChestFixture() {
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            MinecraftServer server = mock(MinecraftServer.class);
            PlayerList playerList = mock(PlayerList.class);
            when(level.getServer()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(playerList);

            viewer = player();
            otherChestViewer = player();
            idlePlayer = player();
            when(playerList.getPlayers()).thenReturn(List.of(viewer, otherChestViewer, idlePlayer));
            otherChestViewer.containerMenu = ChestMenu.threeRows(
                1, otherChestViewer.getInventory(), chestAt(CHEST_POS.east()));

            openChest(chestAt(CHEST_POS), 1);
            fluidData.registerGeneratedSource(SOURCE_SLOT, Fluids.WATER.getFluidType());
        }

        private ServerPlayer player() {
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(level);
            // 只跳过开箱音效/世界调度；菜单槽位与 viewer 位置匹配仍走真实实现。
            when(player.isSpectator()).thenReturn(true);
            Inventory inventory = new Inventory(player);
            when(player.getInventory()).thenReturn(inventory);
            player.containerMenu = new InventoryMenu(inventory, false, player);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            return player;
        }

        ChestBlockEntity chestAt(BlockPos pos) {
            ChestBlockEntity be = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
            be.setLevel(level);
            when(level.getBlockEntity(pos)).thenReturn(be);
            when(level.getBlockState(pos)).thenReturn(be.getBlockState());
            doReturn(new InvWrapper(be)).when(level)
                .getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            return be;
        }

        ChestMenu openChest(ChestBlockEntity be, int menuId) {
            chest = be;
            ChestMenu menu = ChestMenu.threeRows(menuId, viewer.getInventory(), be);
            viewer.containerMenu = menu;
            context = ContainerContexts.resolve(viewer, menu.slots.getFirst());
            assertNotNull(context, "真实单箱槽位必须能反查服务端容器上下文");
            fluidData = ContainerFluidHandler.getOrCreateFluidData(context);
            assertNotNull(fluidData);
            return menu;
        }

        void stopViewing() {
            viewer.containerMenu = new InventoryMenu(viewer.getInventory(), false, viewer);
        }

        void tickAndFlush() {
            fluidData.tick(context);
            FluidFlowServerSync.flushAfterTick(context, fluidData);
        }

        List<FluidFlowSyncPacket> sentPackets(int count) {
            ArgumentCaptor<FluidFlowSyncPacket> captor = ArgumentCaptor.forClass(FluidFlowSyncPacket.class);
            verify(viewer.connection, times(count)).send(captor.capture());
            verifyNoMoreInteractions(viewer.connection);
            verifyNoInteractions(otherChestViewer.connection, idlePlayer.connection);
            return captor.getAllValues();
        }
    }

    // ── 2026-10-06 第 ③ 次泄漏修复的守卫（收尾审查补）────────────

    /**
     * 造一个「玩家的末影箱」上下文 —— 走**生产解析路径**（`ContainerContexts.resolve` 的
     * F-1 末影箱分支），顺带把「resolve ⇒ 末影箱 ctx」这条接线也钉住。
     */
    private static TickableContainerContext enderCtx(ServerPlayer player) {
        PlayerEnderChestContainer chest = new PlayerEnderChestContainer();
        when(player.getEnderChestInventory()).thenReturn(chest);
        when(player.level()).thenReturn(mock(net.minecraft.world.level.Level.class));
        when(player.getStringUUID()).thenReturn("uuid-ec");
        return ContainerContexts.resolve(player, new Slot(chest, 0, 0, 0));
    }

    @Test
    @DisplayName("renderTargetOf：末影箱 ⇒ ENDER_CHEST；BE 容器（无 inventory）⇒ BLOCK")
    void renderTargetOf_twoKinds() {
        ServerPlayer player = mock(ServerPlayer.class);
        assertEquals(FluidFlowClientCache.RenderTarget.ENDER_CHEST,
            FluidFlowServerSync.renderTargetOf(enderCtx(player)), "末影箱");

        // 玩家背包那条分支（getInventory() != null）需要真实 Inventory，构造成本高；
        // 这里钉住「非末影箱且无 inventory ⇒ BLOCK」，背包分支由集成路径覆盖。
        var beCtx = mock(TickableContainerContext.class);
        assertEquals(FluidFlowClientCache.RenderTarget.BLOCK,
            FluidFlowServerSync.renderTargetOf(beCtx), "BE 容器");
    }

    // ── 关界面清理：物品形态容器只能问一次（2026-10-23 真机实录）────────────────
    //
    // 现场：关背包界面时服务端刷了 60 条精妙的 "Error getting backpack wrapper -
    // Stack isn't a backpack"。根因是物品形态分支当时在逐槽循环里 ⇒ 同一个菜单被问
    // N 遍（N = 菜单槽位数），而锚点失效时精妙每次都打一条 ERROR。
    // 修法：把物品形态解析提到循环外，问一次就够（它是菜单级的，与槽位无关）。
    //
    // 本测试的双关断言：① 来源只被问一次；② 逐槽循环没有跑 —— mock 菜单的 slots 字段
    // 是 null，循环一旦跑起来就会 NPE。

    @Test
    @DisplayName("关界面：物品形态容器只问一次且不进逐槽循环（真机 60 条 ERROR 的回归）")
    void onContainerClose_itemForm_asksOnceNotPerSlot() {
        ItemFormContainers.clearForTest();
        try {
            java.util.concurrent.atomic.AtomicInteger asks = new java.util.concurrent.atomic.AtomicInteger();
            ItemFormContainers.register(new ItemFormContainers.Source() {
                @Override public boolean isContainerItem(net.minecraft.world.item.ItemStack stack) { return true; }
                @Override public net.neoforged.neoforge.items.IItemHandler contentHandler(
                        net.minecraft.world.item.ItemStack stack) { return new net.neoforged.neoforge.items.ItemStackHandler(9); }
                @Override public ItemFormContainers.Handle handleOfMenu(
                        AbstractContainerMenu menu, net.minecraft.world.entity.player.Player player) {
                    asks.incrementAndGet();
                    return new ItemFormContainers.Handle(
                        new net.neoforged.neoforge.items.ItemStackHandler(9),
                        java.util.UUID.randomUUID(), 0);
                }
            });

            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(mock(net.minecraft.server.level.ServerLevel.class));

            // slots 保持 null：逐槽循环一旦跑起来就 NPE ⇒ 测试直接失败
            FluidFlowServerSync.onContainerClose(
                new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close(
                    player, mock(AbstractContainerMenu.class)));

            assertEquals(1, asks.get(),
                "物品形态解析是菜单级的 ⇒ 只能问一次；逐槽问会把同一个菜单问 N 遍");
        } finally {
            ItemFormContainers.clearForTest();
        }
    }
}
