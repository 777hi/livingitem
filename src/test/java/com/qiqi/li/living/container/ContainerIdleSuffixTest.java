package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.water.ContainerFluidData;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 静息快路径（Q4a）—— 守「稳定无活物品、无持续流体工作的容器在收口拍后跳过后缀」这条不变量。
 *
 * <p>闩锁语义：进入完整管道即清零；整拍成功且扫描无活物品、无持续工作 ⇒ 置真。
 * 关键判据是<b>扫描直接见到的活物品</b>（{@code hasAnyLivingItem}），不是功能分组 ——
 * 活黑曜石等「有标记、无认领」物品不进分组但必须被看见（负向变异 A 的守卫）。</p>
 */
class ContainerIdleSuffixTest {

    private List<ContainerTickHook> savedHooks;

    @BeforeEach
    void isolateHooksAndCaches() {
        savedHooks = ContainerTickHooks.snapshotForTest();
        ContainerTickHooks.replaceForTest(List.of());
        ContainerLivingItemHandler.clearAllCaches();
    }

    @AfterEach
    void tearDown() {
        ContainerTickHooks.replaceForTest(savedHooks);
        ContainerLivingItemHandler.clearAllCaches();
    }

    private static Level overworld() {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isClientSide()).thenReturn(false);
        // 流体引擎的统一时钟（D-fluid-04 节拍）读 gameTime：冻结的 mock 会让 BFS 永不推进
        long[] tick = {0};
        when(level.getGameTime()).thenAnswer(inv -> tick[0]++);
        return level;
    }

    /** 计数钩子：start / writeback 次数即「完整管道执行了多少拍」的直接观测。 */
    private static final class CountingHook implements ContainerTickHook {
        int starts;
        int writebacks;

        @Override
        public void onTickStart(ContainerContext ctx, TickContext tick) {
            starts++;
        }

        @Override
        public void onWriteback(TickableContainerContext ctx, TickContext tick) {
            writebacks++;
        }
    }

    // ── 1. 稳态静息：首拍完整，之后跳过；LAST_SEEN 仍续期 ──

    @Test
    @DisplayName("静息容器：第一拍完整收口，后续拍跳过后缀，但生命周期盖章照常续期")
    void idleContainer_runsFullOnceThenSkips() {
        Level level = overworld();
        SimpleContainerContext ctx = new SimpleContainerContext(new ItemStackHandler(27), null,
            List.of(new BlockPos(10, 64, 10)), List.of(), level);
        String key = ctx.getCacheKey();
        CountingHook hook = new CountingHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        ContainerLivingItemHandler.processContext(ctx, level);
        ContainerLivingItemHandler.processContext(ctx, level);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(1, hook.starts, "只有第一拍进入开始钩子，后两拍必须走静息快路径");
        assertEquals(1, hook.writebacks, "只有第一拍完成写回");
        assertNull(ctx.getTickContext(), "静息拍不得绑定 TickContext");

        // 快路径不逃过生命周期：把盖章推到阈值外，再跑一拍（静息）⇒ 必须续期、不被 sweep
        long now = System.currentTimeMillis();
        ContainerLivingItemHandler.setLastSeenForTest(key,
            now - ContainerLivingItemHandler.UNSEEN_TTL_MS - 1);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(0, ContainerLivingItemHandler.sweepUnseenContainers(now,
            ContainerLivingItemHandler.UNSEEN_TTL_MS), "静息拍仍要盖章，不得被回收");
        assertEquals(1, hook.starts, "静息拍不进入钩子");
    }

    // ── 2. 唤醒与收口：放活物品立即恢复完整执行；移除后恰好一拍清理 ──

    @Test
    @DisplayName("settled 容器放入活物品下一拍立即执行；移除后恰好一拍清理再静息")
    void livingItemInsertWakes_andRemovalRunsExactlyOneCleanupPass() {
        Level level = overworld();
        ItemStackHandler inventory = new ItemStackHandler(27);
        SimpleContainerContext ctx = new SimpleContainerContext(inventory, null,
            List.of(new BlockPos(11, 64, 11)), List.of(), level);
        CountingHook hook = new CountingHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        ContainerLivingItemHandler.processContext(ctx, level);   // 首拍收口
        ContainerLivingItemHandler.processContext(ctx, level);   // 静息
        assertEquals(1, hook.writebacks);

        ItemStack furnace = new ItemStack(Items.FURNACE);
        LivingItemManager.setLiving(furnace, true);
        inventory.setStackInSlot(0, furnace);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(2, hook.writebacks, "放入活物品必须唤醒完整管道");

        inventory.setStackInSlot(0, ItemStack.EMPTY);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(3, hook.writebacks, "移除后必须完整跑一拍清理（闩锁此时才结算）");

        ContainerLivingItemHandler.processContext(ctx, level);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(3, hook.writebacks, "清理拍之后重新静息");
    }

    // ── 3. 活黑曜石守卫：有标记、无认领的活物品必须被静息判定看见 ──

    @Test
    @DisplayName("有 IS_LIVING 但无功能认领的物品（如活黑曜石）在场 ⇒ 永不进入静息快路径")
    void unclaimedLivingItem_neverSettles() {
        Level level = overworld();
        ItemStackHandler inventory = new ItemStackHandler(27);
        SimpleContainerContext ctx = new SimpleContainerContext(inventory, null,
            List.of(new BlockPos(12, 64, 12)), List.of(), level);
        CountingHook hook = new CountingHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        // 认领清单恒空：任何活物品都不进功能分组（模拟活黑曜石：行为归容器级门面驱动）。
        // 自维持清单不桩改 ⇒ 静息谓词仍走真实三函数的两阶段查询。
        try (MockedStatic<LivingItemManager> ignored =
                 mockStatic(LivingItemManager.class, CALLS_REAL_METHODS)) {
            ignored.when(() -> LivingItemManager.getApplicableFunctions(any(ItemStack.class)))
                .thenReturn(List.of());

            ItemStack obsidianLike = new ItemStack(Items.OBSIDIAN);
            LivingItemManager.setLiving(obsidianLike, true);
            inventory.setStackInSlot(0, obsidianLike);

            for (int i = 0; i < 3; i++) {
                ContainerLivingItemHandler.processContext(ctx, level);
            }
            assertEquals(3, hook.writebacks,
                "分组为空但扫描见到了活物品 ⇒ 每拍都必须完整执行，不得用 grouped.isEmpty() 判静息");
        }
    }

    // ── 4. 流体工作阻止 settling：有源期间每拍完整；源清空收口一拍后才静息 ──

    @Test
    @DisplayName("非空流体数据阻止静息；源清空后的清屏拍完成才允许跳过")
    void fluidWorkBlocksSettling_untilCleared() {
        Level level = overworld();
        SimpleContainerContext ctx = new SimpleContainerContext(new ItemStackHandler(27), null,
            List.of(new BlockPos(13, 64, 13)), List.of(), level);
        CountingHook hook = new CountingHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        // 预置派生源即可让 isEmpty() 为假（flows 为空也不影响），无需注册流动行为
        ContainerFluidData.getOrCreate(ctx).registerGeneratedSource(0, Fluids.WATER.getFluidType());
        ContainerLivingItemHandler.processContext(ctx, level);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(2, hook.writebacks, "有源期间每拍都必须完整执行（持续无物品工作）");

        // 注意语义：removeGeneratedSource 只删派生源账本，水本身仍在 flows（容器资产不蒸发）
        // ⇒ isEmpty() 依旧为假。真正的「数据清空」入口是 clearAll()（全部汲走 / 清场）。
        ContainerFluidData.getOrCreate(ctx).clearAll();
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(3, hook.writebacks, "数据清空后的清屏拍必须完整");

        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(3, hook.writebacks, "流体清空并收口后才允许静息");
    }

    // ── 5. 异常不结算：失败拍不得置闩锁，修复后下一拍补清理 ──

    @Test
    @DisplayName("后缀中途抛异常的拍不得结算闩锁；恢复后必须再完整跑一拍才静息")
    void exceptionDuringSuffix_doesNotSettleLatch() {
        Level level = overworld();
        ItemStackHandler inventory = new ItemStackHandler(27);
        ThrottledContext ctx = new ThrottledContext(inventory, level, new BlockPos(14, 64, 14));
        CountingHook hook = new CountingHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        ContainerLivingItemHandler.processContext(ctx, level);   // 首拍收口
        ContainerLivingItemHandler.processContext(ctx, level);   // 静息
        assertEquals(1, hook.writebacks);

        ItemStack furnace = new ItemStack(Items.FURNACE);
        LivingItemManager.setLiving(furnace, true);
        inventory.setStackInSlot(0, furnace);
        ctx.throwNextFlush = true;
        assertThrows(IllegalStateException.class,
            () -> ContainerLivingItemHandler.processContext(ctx, level),
            "脏槽刷新失败必须原样抛出");
        assertEquals(2, hook.writebacks, "失败拍已执行到写回");

        inventory.setStackInSlot(0, ItemStack.EMPTY);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(3, hook.writebacks, "失败拍不得结算闩锁 ⇒ 移除后的清理拍必须完整");

        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(3, hook.writebacks, "补跑的清理拍成功后才重新静息");
    }

    /** flushDirtySlots 可控抛异常的测试替身（异常手法同 ContainerTickIsolationTest）。 */
    private static final class ThrottledContext extends SimpleContainerContext {
        boolean throwNextFlush;

        ThrottledContext(ItemStackHandler inventory, Level level, BlockPos pos) {
            super(inventory, null, List.of(pos), List.of(), level);
        }

        @Override
        public void flushDirtySlots() {
            if (throwNextFlush) {
                throwNextFlush = false;
                throw new IllegalStateException("flush");
            }
            super.flushDirtySlots();
        }
    }

    // ── 6. 普通货物变化不解除静息 ──

    @Test
    @DisplayName("settled 后放入普通货物：内容签名照常扫描，但后缀保持跳过")
    void plainCargoChange_keepsIdle() {
        Level level = overworld();
        ItemStackHandler inventory = new ItemStackHandler(27);
        SimpleContainerContext ctx = new SimpleContainerContext(inventory, null,
            List.of(new BlockPos(15, 64, 15)), List.of(), level);
        CountingHook hook = new CountingHook();
        ContainerTickHooks.replaceForTest(List.of(hook));

        ContainerLivingItemHandler.processContext(ctx, level);   // 首拍收口
        ContainerLivingItemHandler.processContext(ctx, level);   // 静息
        assertEquals(1, hook.writebacks);

        inventory.setStackInSlot(3, new ItemStack(Items.IRON_INGOT, 16));
        ContainerLivingItemHandler.processContext(ctx, level);
        ContainerLivingItemHandler.processContext(ctx, level);
        assertEquals(1, hook.writebacks, "普通货物变化不需要完整管道，静息保持");
    }
}
