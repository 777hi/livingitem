package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;

import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickableContainerContext;

/**
 * 流体快照同步的边沿状态机守卫（2026-10-04）——
 * 「汲走最后一个源 ⇒ 数据清空 ⇒ 必须下发一次空快照清掉客户端残留渲染」。
 *
 * <p>bug 现场：驱动的 {@code !isEmpty()} 门与 flush 的同名校验把「刚清空」一起挡掉，
 * 服务器不再发包，客户端旧水渲染永不消失。</p>
 */
class FluidFlowServerSyncTest {

    @AfterEach
    void tearDown() {
        FluidFlowServerSync.clearForTest();
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
    @DisplayName("末影箱派发**必须判 viewer**：玩家正在看普通箱子 ⇒ 不下发（否则串到别的界面）")
    void enderDispatch_requiresViewer() {
        ServerPlayer player = mock(ServerPlayer.class);
        var ender = enderCtx(player);

        // 玩家正在看自己的末影箱 ⇒ 发
        ChestMenu enderMenu = mock(ChestMenu.class);
        when(enderMenu.getContainer()).thenReturn(new PlayerEnderChestContainer());
        player.containerMenu = enderMenu;
        assertTrue(FluidFlowServerSync.shouldDispatch(ender, player), "看的是末影箱 ⇒ 下发");

        // 玩家关掉末影箱、打开普通箱子 ⇒ 不发（第 ③ 次泄漏的根因就在这条）
        ChestMenu chestMenu = mock(ChestMenu.class);
        when(chestMenu.getContainer()).thenReturn(new SimpleContainer(27));
        player.containerMenu = chestMenu;
        assertFalse(FluidFlowServerSync.shouldDispatch(ender, player),
            "看的是普通箱子 ⇒ **不**下发（否则末影箱的水渲染到箱子界面上）");
    }

    @Test
    @DisplayName("非末影箱上下文不受 viewer 门影响（背包 / BE 走各自分支）")
    void nonEnderDispatch_notGated() {
        ServerPlayer player = mock(ServerPlayer.class);
        player.containerMenu = mock(AbstractContainerMenu.class);
        var ctx = new SimpleContainerContext(new net.neoforged.neoforge.items.ItemStackHandler(9));
        assertTrue(FluidFlowServerSync.shouldDispatch(ctx, player), "BE/背包容器走 dispatch 自己的匹配");
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
}
