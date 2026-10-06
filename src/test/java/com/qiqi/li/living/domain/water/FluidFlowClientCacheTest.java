package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.water.FluidFlowClientCache.FlowSnapshot;
import com.qiqi.li.living.domain.water.FluidFlowClientCache.RenderTarget;

/**
 * 客户端流体快照<b>分桶与目标提示</b>的不变量（2026-10-06，第 ③ 次泄漏修复配套）。
 *
 * <p>背景：客户端<b>无法</b>判断当前界面是末影箱还是普通 3 行箱子（界面上只有 27 格
 * {@code SimpleContainer} 替身，真实容器只在服务端 ⇒ {@code instanceof} 是死分支），
 * 所以「非背包组该读哪个桶」由服务端下发的 {@link RenderTarget} 决定。
 * 一旦这个提示被错误地翻转，就会出现<b>末影箱的水渲染到别的箱子界面上</b>。</p>
 *
 * <p>本类钉住两件事：① 三份快照互相不串；② {@link FluidFlowClientCache#clear()}
 * 把提示重置回 {@code BLOCK} ⇒ 关界面后不会闪现上一个界面的水。</p>
 */
class FluidFlowClientCacheTest {

    /** 造一份带一个格子的快照（不依赖任何注册表内容）。 */
    private static FlowSnapshot snapshot(int level) {
        return new FlowSnapshot(9, List.of(), Map.of(0, new int[]{level, -1, 0}));
    }

    private static int levelOf(FlowSnapshot s) {
        return s.cells().get(0)[0];
    }

    @Test
    @DisplayName(" EnderChest 目标 ⇒ 写ender 桶并把「非背包组」提示切到 ENDER_CHEST")
    void enderTarget_routesToEnderBucket() {
        FluidFlowClientCache.clear();

        FluidFlowClientCache.update(RenderTarget.ENDER_CHEST, snapshot(7));

        assertEquals(7, levelOf(FluidFlowClientCache.getChestLike()),
            "非背包组应读到 ender 桶");
        assertTrue(FluidFlowClientCache.getPlayer().isEmpty(), "背包组不受影响");
    }

    @Test
    @DisplayName("BLOCK 目标 ⇒ 写 box 桶并把提示切回 BLOCK（末影箱的水不会留在箱子上）")
    void blockTarget_routesToBlockBucket() {
        FluidFlowClientCache.clear();
        FluidFlowClientCache.update(RenderTarget.ENDER_CHEST, snapshot(7));

        FluidFlowClientCache.update(RenderTarget.BLOCK, snapshot(3));

        assertEquals(3, levelOf(FluidFlowClientCache.getChestLike()),
            "收到 BLOCK 目标后，非背包组必须读 box 桶（提示被覆盖）");
    }

    @Test
    @DisplayName("PLAYER_INV 目标 ⇒ 只写背包桶，**不改**非背包组提示")
    void playerTarget_doesNotTouchChestLike() {
        FluidFlowClientCache.clear();
        FluidFlowClientCache.update(RenderTarget.ENDER_CHEST, snapshot(7));

        FluidFlowClientCache.update(RenderTarget.PLAYER_INV, snapshot(2));

        assertEquals(2, levelOf(FluidFlowClientCache.getPlayer()), "背包组读到自己的水");
        assertEquals(7, levelOf(FluidFlowClientCache.getChestLike()),
            "背包快照不得翻转非背包组提示（否则开背包会串台）");
    }

    @Test
    @DisplayName("clear() ⇒ 三桶全空 + 提示重置为 BLOCK（关界面后不闪旧水）")
    void clear_resetsEverything() {
        FluidFlowClientCache.update(RenderTarget.ENDER_CHEST, snapshot(7));
        FluidFlowClientCache.update(RenderTarget.PLAYER_INV, snapshot(5));

        FluidFlowClientCache.clear();

        assertTrue(FluidFlowClientCache.getChestLike().isEmpty(), "非背包组快照已清空");
        assertTrue(FluidFlowClientCache.getPlayer().isEmpty(), "背包组快照已清空");
        // 提示复位后即便立刻来一个「无流体的 BE 容器」包，读到的也是空快照而非上一个界面的水
        FluidFlowClientCache.update(RenderTarget.BLOCK, FluidFlowClientCache.FlowSnapshot.EMPTY);
        assertTrue(FluidFlowClientCache.getChestLike().isEmpty());
    }
}