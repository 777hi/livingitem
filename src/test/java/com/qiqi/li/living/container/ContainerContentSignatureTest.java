package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Q2 回归：内容签名必须检出「同 ID 同数量、组件不同」的原版途径变化。
 *
 * <p>原始洞（TODO Q2）：签名逐槽只混「物品 id + 数量」，两个不同内容量的活箱子
 * 经原版 GUI 交换时签名不变 → 修订计数不动 → 快照按旧容量多算一拍（漏斗过滤链
 * 同理）。修复后签名混入 {@link ItemStack#hashItemAndComponents}（全部组件）并保留
 * 逐槽 count。</p>
 */
class ContainerContentSignatureTest {

    private static final int SIZE = 27;
    private static final int WIDTH = 9;

    private static ItemStack livingChestWithItems(int filledSlots) {
        ItemStack chest = new ItemStack(Items.CHEST);
        LivingItemManager.setLiving(chest, true);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < filledSlots; i++) {
            items.add(new ItemStack(Items.DIAMOND, 1));
        }
        LivingChestFunction.setItems(chest, items);
        return chest;
    }

    private static FakeContainerContext ctx() {
        return new FakeContainerContext(SIZE, WIDTH, "sig_" + UUID.randomUUID());
    }

    @Test
    @DisplayName("Q2：原版途径交换不同内容量的同 ID 活箱子 ⇒ 修订计数 bump、快照按新容量重建")
    void vanillaComponentSwap_bumpsRevision_andSnapshotReflectsNewContents() {
        var ctx = ctx();
        ctx.set(0, livingChestWithItems(2));
        ContainerLivingItemHandler.syncContentRevision(ctx);
        long baseline = ContainerLivingItemHandler.getContainerRevision(ctx);

        var tick = new TickContext(ctx);
        ContainerSnapshot before = ContainerLivingItemHandler.getCachedSnapshot(ctx, baseline, tick);
        assertEquals(2, before.getChestSnapshot(0).usedSlots(),
            "前置：快照容量应来自旧箱子（2 格占用）");

        // 原版途径：rawSet 不经 setItem ⇒ 不 bump 修订计数；同 ID 同数量、CONTAINER 组件不同
        ctx.rawSet(0, livingChestWithItems(5));

        // 修复前：签名（仅 id+count）不变 ⇒ rev 不动 ⇒ 快照仍按 2 格容量算一拍。
        ContainerLivingItemHandler.syncContentRevision(ctx);
        long after = ContainerLivingItemHandler.getContainerRevision(ctx);
        assertTrue(after > baseline, "组件变化必须经签名兜底 bump 修订计数");

        ContainerSnapshot refreshed = ContainerLivingItemHandler.getCachedSnapshot(ctx, after, tick);
        assertNotSame(before, refreshed, "修订计数变了 ⇒ 缓存快照必须重建");
        assertEquals(5, refreshed.getChestSnapshot(0).usedSlots(),
            "新快照容量必须来自换入箱子（5 格占用）——错一拍就是 isFull/余量误判");
    }

    @Test
    @DisplayName("Q2 接线：真实 processContext 单遍扫描检出组件变化并 bump 修订计数")
    void processContext_componentChangeViaUnderlyingHandler_bumpsRevision() {
        ItemStackHandler handler = new ItemStackHandler(SIZE);
        SimpleContainerContext ctx = new SimpleContainerContext(handler);
        Level level = org.mockito.Mockito.mock(Level.class);
        LivingItemFunction noOp = new LivingItemFunction() {
            @Override public boolean canApply(ItemStack stack) { return LivingItemManager.isLivingItem(stack); }
            @Override public String getFunctionId() { return "q2_signature_probe"; }
            @Override public void tick(List<SlotEntry> entries, ContainerContext container,
                                       TickContext tick, Level ignored) { }
        };

        // 生产入口需要至少一个分组（否则无自维持函数时在 TickContext 前早退）。
        try (MockedStatic<LivingItemManager> functions = mockStatic(LivingItemManager.class, CALLS_REAL_METHODS)) {
            functions.when(() -> LivingItemManager.getApplicableFunctions(any(ItemStack.class)))
                .thenAnswer(invocation -> noOp.canApply(invocation.getArgument(0))
                    ? List.of(noOp) : List.of());
            functions.when(LivingItemManager::getSelfSustainingFunctions).thenReturn(List.of());

            handler.setStackInSlot(0, livingChestWithItems(2));
            ContainerLivingItemHandler.processContext(ctx, level);
            long baseline = ContainerLivingItemHandler.getContainerRevision(ctx);

            // 直接改底层 handler = 原版 GUI / 漏斗途径：绕过 ctx.setItem，不显式 bump。
            handler.setStackInSlot(0, livingChestWithItems(5));
            assertEquals(baseline, ContainerLivingItemHandler.getContainerRevision(ctx),
                "前置：底层改写本身不能 bump，必须由 processContext 同遍签名兜底检出");

            ContainerLivingItemHandler.processContext(ctx, level);
            assertTrue(ContainerLivingItemHandler.getContainerRevision(ctx) > baseline,
                "真实 processContext 接线必须把扫描得到的组件签名交给 syncContentRevision");
        } finally {
            ContainerLivingItemHandler.clearAllCaches();
        }
    }

    @Test
    @DisplayName("Q2 边界：hashItemAndComponents 不含数量 ⇒ 数量变化仍必须检出")
    void countChange_viaVanillaPath_bumpsRevision() {
        var ctx = ctx();
        ctx.set(0, living(Items.REDSTONE, 5));
        ContainerLivingItemHandler.syncContentRevision(ctx);
        long baseline = ContainerLivingItemHandler.getContainerRevision(ctx);

        // 同物品同组件、仅数量不同（原版分堆/合堆）
        ctx.rawSet(0, living(Items.REDSTONE, 9));
        ContainerLivingItemHandler.syncContentRevision(ctx);

        assertTrue(ContainerLivingItemHandler.getContainerRevision(ctx) > baseline,
            "组件哈希不含数量 ⇒ count 必须单独混入，否则分堆合堆漏检");
    }

    @Test
    @DisplayName("Q2 边界：同 ID 同数量同组件的等值替换不 bump（稳态不空转）")
    void identicalStackSwap_doesNotBump() {
        var ctx = ctx();
        ctx.set(0, livingChestWithItems(2));
        ContainerLivingItemHandler.syncContentRevision(ctx);
        long baseline = ContainerLivingItemHandler.getContainerRevision(ctx);

        // 等值新实例（同 id / 同数量 / 同组件值）—— 全量组件哈希不得引起虚假失效
        ctx.rawSet(0, livingChestWithItems(2));
        ContainerLivingItemHandler.syncContentRevision(ctx);

        assertEquals(baseline, ContainerLivingItemHandler.getContainerRevision(ctx),
            "组件值相等 ⇒ 签名不变，稳态跳过不得被打破");
    }

    @Test
    @DisplayName("空槽混入不变：物品在槽位间移动（含清空）必须检出")
    void slotMoves_stillDetected() {
        var ctx = ctx();
        ctx.set(3, living(Items.REDSTONE, 1));
        ContainerLivingItemHandler.syncContentRevision(ctx);
        long baseline = ContainerLivingItemHandler.getContainerRevision(ctx);

        ctx.rawSet(3, ItemStack.EMPTY);
        ctx.rawSet(4, living(Items.REDSTONE, 1));
        ContainerLivingItemHandler.syncContentRevision(ctx);

        assertTrue(ContainerLivingItemHandler.getContainerRevision(ctx) > baseline,
            "空槽参与混入 ⇒ 槽位移动必须检出（旧行为保持）");
    }

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }
}
