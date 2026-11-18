package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 槽位数量操作原语（{@code move} / {@code produce} / {@code consume} / {@code transform}）
 * 的守恒与违约守卫 —— TODO「物品传输」1-②③ 的判据面。
 *
 * <p>守什么：</p>
 * <ol>
 *   <li><b>move 守恒</b>：任意移动前后总量不变；差额方向只会是「少搬」（部分交付），
 *       绝不「多搬」；源扣减不成立 ⇒ 整拍撤销目标写入（复制面）；</li>
 *   <li><b>PHYSICAL = A3</b>：不问准入与抽取权限，容量只问槽位上限（流体推动的现行口径）；</li>
 *   <li><b>produce 差额留痕不回滚</b>：已放入部分是真产物；无源槽 ⇒ 无复制面；</li>
 *   <li><b>consume 扣不到 = 本拍不动</b>：写回不符恢复原状返回 0；</li>
 *   <li><b>transform 失败还原</b>：不可拆整槽变换要么成要么槽原样（I-C13）。</li>
 * </ol>
 *
 * <p>参数化双实现：{@code handlerBacked=true} = SimpleContainerContext（NeoForge handler
 * 后端，生产主路径）；{@code false} = FakeContainerContext（纯接口默认实现）。
 * 准入拒绝 / 抽取拒绝 / 违约注入只在 handler 形状上可表达，单独成组。</p>
 *
 * <p>负向验证（unit-testing.md 纪律，2026-11-05 实测）：摘掉 move 的源侧回读 ⇒
 * {@code sourceWriteFailureRollsBackTarget} 红（复制面 32+32 被抓住）；produce 改按计划量
 * 返回 ⇒ {@code produceKeepsPartialLandingWhenHandlerLies} 红（10 ≠ 实收 6）；consume 摘掉
 * 写回核验 ⇒ {@code consumeTreatsWriteFailureAsNothingConsumed} 红（源被扣空）。三者均确认
 * 会红后恢复实现，其余 29 例不受影响。</p>
 */
class ContainerSlotOpsTest {

    // ── 替身 handler（照 ContainerMoveStackTest / ContainerFluidConservationTest 的既有形状） ──

    /**
     * 槽位上限按槽可配置的 handler。{@code oversize=true} = 抽屉语义（槽上限权威）。
     * {@code noInsertSlots} 钉「准入拒绝」维，{@code noExtractSlots} 钉「抽取权限」维。
     */
    private static class LimitedHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        final int[] limits;
        final boolean oversize;
        final java.util.Set<Integer> noExtractSlots = new java.util.HashSet<>();
        final java.util.Set<Integer> noInsertSlots = new java.util.HashSet<>();

        LimitedHandler(int... limits) {
            this(false, limits);
        }

        LimitedHandler(boolean oversize, int... limits) {
            this.oversize = oversize;
            this.limits = limits;
            this.slots = new ItemStack[limits.length];
            for (int i = 0; i < slots.length; i++) slots[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public int getSlotLimit(int slot) { return limits[slot]; }
        /** 准入拒绝同时表达进 NeoForge 的规范准入口（insertItem 空槽接纳也查它）。 */
        @Override public boolean isItemValid(int slot, ItemStack stack) { return !noInsertSlots.contains(slot); }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            int maxStack = oversize
                ? getSlotLimit(slot)
                : Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (noInsertSlots.contains(slot) || existing.isEmpty()) {
                if (noInsertSlots.contains(slot)) return stack;   // 准入拒绝
                int toInsert = Math.min(stack.getCount(), maxStack);
                if (!simulate) slots[slot] = stack.copyWithCount(toInsert);
                return remainderOf(stack, toInsert);
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = maxStack - existing.getCount();
                if (space <= 0) return stack;
                int toInsert = Math.min(stack.getCount(), space);
                if (!simulate) slots[slot].grow(toInsert);
                return remainderOf(stack, toInsert);
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (noExtractSlots.contains(slot)) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) slots[slot].shrink(toExtract);
            return result;
        }

        private static ItemStack remainderOf(ItemStack stack, int inserted) {
            ItemStack remainder = stack.copy();
            remainder.shrink(inserted);
            return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
        }
    }

    /** {@code setStackInSlot} 对指定槽<b>静默丢弃</b>写入 —— 复刻第三方「setStackInSlot 静默失败」。 */
    private static class SilentWriteHandler extends LimitedHandler {
        final java.util.Set<Integer> silentSlots = new java.util.HashSet<>();

        SilentWriteHandler(int... limits) { super(limits); }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            if (!silentSlots.contains(slot)) super.setStackInSlot(slot, stack);
        }
    }

    /** {@code setStackInSlot} 对指定槽首次写入时<b>整库重定向</b>成别的东西（之后恢复老实）。 */
    private static class FirstWriteRedirectHandler extends LimitedHandler {
        final int redirectSlot;
        boolean redirected;

        FirstWriteRedirectHandler(int redirectSlot, int... limits) {
            super(limits);
            this.redirectSlot = redirectSlot;
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            if (slot == redirectSlot && !redirected) {
                redirected = true;
                super.setStackInSlot(slot, new ItemStack(Items.GOLD_INGOT, stack.getCount()));
                return;
            }
            super.setStackInSlot(slot, stack);
        }
    }

    /** {@code setStackInSlot} 对指定槽按自己口径截断 —— 复刻「simulate 撒谎」（说能收 N 实收 M&lt;N）。 */
    private static class ClampingWriteHandler extends LimitedHandler {
        final int clampSlot;
        final int clampMax;

        ClampingWriteHandler(int clampSlot, int clampMax, int... limits) {
            super(limits);
            this.clampSlot = clampSlot;
            this.clampMax = clampMax;
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            if (slot == clampSlot && !stack.isEmpty()) {
                super.setStackInSlot(slot, stack.copyWithCount(Math.min(stack.getCount(), clampMax)));
                return;
            }
            super.setStackInSlot(slot, stack);
        }
    }

    // ── 双实现 fixture ─────────────────────────────────────────

    /**
     * {@code handlerBacked} = SimpleContainerContext（limits 传给 LimitedHandler）；
     * 否则 FakeContainerContext（limits = withSlotLimits，null = 默认 64）。
     */
    private static ContainerContext newCtx(boolean handlerBacked, int size, int[] limits) {
        if (handlerBacked) {
            int[] ls = limits != null ? limits : fill(size, 64);
            return new SimpleContainerContext(new LimitedHandler(ls));
        }
        FakeContainerContext fake = new FakeContainerContext(size, 9);
        if (limits != null) fake.withSlotLimits(limits);
        return fake;
    }

    private static int[] fill(int size, int value) {
        int[] ls = new int[size];
        java.util.Arrays.fill(ls, value);
        return ls;
    }

    private static int total(ContainerContext ctx) {
        int sum = 0;
        for (int i = 0; i < ctx.getSize(); i++) sum += ctx.getItem(i).getCount();
        return sum;
    }

    private static ItemStack stackOf(ContainerContext ctx, int slot) {
        return ctx.getItem(slot);
    }

    // ── move：六场景 × 双实现 ──────────────────────────────────

    @ParameterizedTest(name = "{0}：整栈移动到空槽 —— 源清空、目标收货、总量守恒")
    @ValueSource(booleans = {true, false})
    void moveWholeStack_conserves(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 32));

        MoveResult result = ctx.move(0, 1, 32);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(32, success.moved());
        assertTrue(stackOf(ctx, 0).isEmpty(), "源清空");
        assertEquals(32, stackOf(ctx, 1).getCount(), "目标收货");
        assertEquals(32, total(ctx), "总量守恒");
    }

    @ParameterizedTest(name = "{0}：同类合并 —— 目标非空同类，合并收货")
    @ValueSource(booleans = {true, false})
    void moveMergesIntoSameType(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 32));
        ctx.setItem(1, new ItemStack(Items.REDSTONE, 10));

        MoveResult result = ctx.move(0, 1, 32);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(32, success.moved(), "目标放得下 ⇒ 全部搬走");
        assertTrue(stackOf(ctx, 0).isEmpty());
        assertEquals(42, stackOf(ctx, 1).getCount(), "10 + 32 合并");
        assertEquals(42, total(ctx));
    }

    @ParameterizedTest(name = "{0}：部分交付 —— 目标只剩 14 空间，搬 14 差 18 留源（I-C12 移动交付量）")
    @ValueSource(booleans = {true, false})
    void moveDeliversPartially(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, fill(2, 64));
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 32));
        ctx.setItem(1, new ItemStack(Items.REDSTONE, 50));

        MoveResult result = ctx.move(0, 1, 32);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(14, success.moved(), "moved ≤ amount");
        assertEquals(18, stackOf(ctx, 0).getCount(), "差额原样留在源槽");
        assertEquals(64, stackOf(ctx, 1).getCount());
        assertEquals(82, total(ctx), "总量守恒（50 + 32 = 64 + 18）");
    }

    @ParameterizedTest(name = "{0}：异类目标 ⇒ TARGET_OCCUPIED，状态不变")
    @ValueSource(booleans = {true, false})
    void moveRejectsDifferentItemTarget(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 32));
        ctx.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));

        MoveResult result = ctx.move(0, 1, 32);

        assertEquals(MoveResult.Reason.TARGET_OCCUPIED,
            assertInstanceOf(MoveResult.Rejected.class, result).reason());
        assertEquals(32, stackOf(ctx, 0).getCount(), "状态未变");
        assertEquals(Items.GOLD_INGOT, stackOf(ctx, 1).getItem(), "状态未变");
    }

    @ParameterizedTest(name = "{0}：目标装不下任何一份 ⇒ TARGET_INSUFFICIENT，状态不变")
    @ValueSource(booleans = {true, false})
    void moveRejectsFullTarget(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, fill(2, 64));
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 32));
        ctx.setItem(1, new ItemStack(Items.REDSTONE, 64));

        MoveResult result = ctx.move(0, 1, 32);

        assertEquals(MoveResult.Reason.TARGET_INSUFFICIENT,
            assertInstanceOf(MoveResult.Rejected.class, result).reason());
        assertEquals(32, stackOf(ctx, 0).getCount());
        assertEquals(64, stackOf(ctx, 1).getCount());
        assertEquals(96, total(ctx));
    }

    @ParameterizedTest(name = "{0}：undo 恢复合并前的两槽状态（防复制）")
    @ValueSource(booleans = {true, false})
    void undoRestoresBothSlotsAfterMerge(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 32));
        ctx.setItem(1, new ItemStack(Items.REDSTONE, 10));

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, ctx.move(0, 1, 32));
        assertTrue(ctx.undo(success.undo()), "撤销应成功");

        assertEquals(32, stackOf(ctx, 0).getCount(), "源恢复");
        assertEquals(10, stackOf(ctx, 1).getCount(), "目标恢复到合并前 —— 否则两槽多出 32 = 复制");
        assertEquals(42, total(ctx));
    }

    @Test
    @DisplayName("move：amount≤0 / 越界 / 同槽 ⇒ INVALID_ADDRESS")
    void moveRejectsInvalidAddress() {
        ContainerContext ctx = newCtx(true, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 1));

        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.move(0, 0, 1)).reason());
        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.move(0, 9, 1)).reason());
        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.move(0, 1, 0)).reason());
    }

    @Test
    @DisplayName("I-C12 抽取权限（STANDARD）：源槽只出不进 ⇒ SOURCE_NOT_EXTRACTABLE")
    void standardMoveRejectsUnextractableSource() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        h.noExtractSlots.add(0);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.move(0, 1, 32);

        assertEquals(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE,
            assertInstanceOf(MoveResult.Rejected.class, result).reason(),
            "容量与准入都通过也不搬：抽取权限是第三维");
        assertEquals(32, stackOf(ctx, 0).getCount(), "源槽一个都没少");
    }

    // ── move PHYSICAL（A3 口径）────────────────────────────────

    @Test
    @DisplayName("PHYSICAL 无视准入：目标槽拒收该物品仍写入（水流搬运不是漏斗插入）")
    void physicalIgnoresAdmission() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        h.noInsertSlots.add(1);   // STANDARD 会因 simulateInsertItem=0 拒绝
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult standard = ctx.move(0, 1, 32, MovePolicy.STANDARD);
        assertEquals(MoveResult.Reason.TARGET_INSUFFICIENT,
            assertInstanceOf(MoveResult.Rejected.class, standard).reason(),
            "STANDARD 尊重准入（对照）");

        MoveResult physical = ctx.move(0, 1, 32, MovePolicy.PHYSICAL);
        assertInstanceOf(MoveResult.Success.class, physical, "PHYSICAL 不问准入");
        assertEquals(32, stackOf(ctx, 1).getCount());
        assertEquals(32, total(ctx));
    }

    @Test
    @DisplayName("PHYSICAL 无视抽取权限：源槽只出不进仍可推走（遗留②未拍板前保持流体现行口径）")
    void physicalIgnoresExtractPermission() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        h.noExtractSlots.add(0);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult standard = ctx.move(0, 1, 32, MovePolicy.STANDARD);
        assertEquals(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE,
            assertInstanceOf(MoveResult.Rejected.class, standard).reason(), "STANDARD 对照");

        MoveResult physical = ctx.move(0, 1, 32, MovePolicy.PHYSICAL);
        assertInstanceOf(MoveResult.Success.class, physical);
        assertTrue(stackOf(ctx, 0).isEmpty());
    }

    @Test
    @DisplayName("PHYSICAL 容量只问槽位上限：500 个进 64 上限的槽 ⇒ 搬 64 差 436 留源（A1 判据在原语级）")
    void physicalBudgetsBySlotLimitOnly() {
        LimitedHandler h = new LimitedHandler(512, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.move(0, 1, 500, MovePolicy.PHYSICAL);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(64, success.moved(), "planned = min(amount, slotLimit − existing)");
        assertEquals(436, stackOf(ctx, 0).getCount(), "差额留在源槽 —— 不是 clamp 截断丢 436");
        assertEquals(64, stackOf(ctx, 1).getCount());
        assertEquals(500, total(ctx));
    }

    // ── move：违约三态 ─────────────────────────────────────────

    @Test
    @DisplayName("源写回静默失败 ⇒ BackendViolation + 目标整拍撤销（目标增量+源未减=复制）")
    void sourceWriteFailureRollsBackTarget() {
        SilentWriteHandler h = new SilentWriteHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        h.silentSlots.add(0);   // 源槽写不进去
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.move(0, 1, 32);

        assertInstanceOf(MoveResult.BackendViolation.class, result,
            "simulate 通过、实写不符 ⇒ 后端违约");
        assertEquals(32, stackOf(ctx, 0).getCount(), "源原样");
        assertTrue(stackOf(ctx, 1).isEmpty(), "目标写入已撤销 —— 否则目标 32 + 源 32 = 复制");
        assertEquals(32, total(ctx), "守恒恢复");
    }

    @Test
    @DisplayName("目标写入被整库重定向 ⇒ BackendViolation + 目标恢复原状")
    void redirectedTargetWriteIsRestored() {
        FirstWriteRedirectHandler h = new FirstWriteRedirectHandler(1, 64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.move(0, 1, 32);

        assertInstanceOf(MoveResult.BackendViolation.class, result);
        assertEquals(32, stackOf(ctx, 0).getCount(), "源未扣（目标没收到货就不扣源）");
        assertTrue(stackOf(ctx, 1).isEmpty(), "重定向的黄金被还原");
        assertEquals(32, total(ctx));
    }

    @Test
    @DisplayName("无精确写的后端照样能 move（setItem 回退路径 + 回读核验；对照 moveStack 的 Unsupported）")
    void moveWorksWithoutExactWrite() {
        PlainHandlerLike h = new PlainHandlerLike(2);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.move(0, 1, 32);

        assertInstanceOf(MoveResult.Success.class, result,
            "move 不设 EXACT_WRITE 门：回退路径 + 回读足够");
        assertTrue(stackOf(ctx, 0).isEmpty());
        assertEquals(32, stackOf(ctx, 1).getCount());
    }

    /** 无 {@code setStackInSlot} 的只读形状（照 ContainerMoveStackTest.PlainHandler 简化）。 */
    private static class PlainHandlerLike implements net.neoforged.neoforge.items.IItemHandler {
        final ItemStack[] slots;

        PlainHandlerLike(int size) {
            this.slots = new ItemStack[size];
            for (int i = 0; i < size; i++) slots[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !slots[slot].isEmpty()) return stack;
            int toInsert = Math.min(stack.getCount(), 64);
            if (!simulate) slots[slot] = stack.copyWithCount(toInsert);
            ItemStack rest = stack.copy();
            rest.shrink(toInsert);
            return rest.isEmpty() ? ItemStack.EMPTY : rest;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack existing = slots[slot];
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(toExtract);
            if (!simulate) slots[slot].shrink(toExtract);
            return result;
        }
    }

    // ── produce ────────────────────────────────────────────────

    @Test
    @DisplayName("produce 空槽整份放入")
    void produceIntoEmptySlot() {
        ContainerContext ctx = newCtx(true, 2, null);
        int actual = ctx.produce(1, new ItemStack(Items.IRON_INGOT), 10);
        assertEquals(10, actual);
        assertEquals(10, stackOf(ctx, 1).getCount());
    }

    @Test
    @DisplayName("produce 合并只放到剩余空间为止，返回实际量（余量归调用方）")
    void produceMergesUpToSpace() {
        ContainerContext ctx = newCtx(true, 2, fill(2, 64));
        ctx.setItem(1, new ItemStack(Items.IRON_INGOT, 60));

        int actual = ctx.produce(1, new ItemStack(Items.IRON_INGOT), 10);

        assertEquals(4, actual, "只放进 4 个");
        assertEquals(64, stackOf(ctx, 1).getCount());
    }

    @Test
    @DisplayName("produce 异类占据 ⇒ 0（内容感知默认：异类放不进）")
    void produceRejectsDifferentItem() {
        ContainerContext ctx = newCtx(true, 2, null);
        ctx.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));
        assertEquals(0, ctx.produce(1, new ItemStack(Items.IRON_INGOT), 10));
        assertEquals(3, stackOf(ctx, 1).getCount(), "状态未变");
    }

    @Test
    @DisplayName("produce 超槽位上限 ⇒ 放到上限为止（耕地空槽路径的容量前置判据）")
    void produceRespectsSlotLimit() {
        ContainerContext ctx = newCtx(false, 2, fill(2, 4));
        int actual = ctx.produce(1, new ItemStack(Items.WHEAT), 10);
        assertEquals(4, actual, "槽位上限 4 是权威（旧实现 setItem 后被 clamp 静默截断）");
        assertEquals(4, stackOf(ctx, 1).getCount());
    }

    @Test
    @DisplayName("produce simulate 撒谎 ⇒ 返回实收数、不回滚（放入部分是真产物）")
    void produceKeepsPartialLandingWhenHandlerLies() {
        ClampingWriteHandler h = new ClampingWriteHandler(1, 6, 64, 64);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        int actual = ctx.produce(1, new ItemStack(Items.IRON_INGOT), 10);

        assertEquals(6, actual, "预算 10、实收 6 —— 差额留痕不回滚");
        assertEquals(6, stackOf(ctx, 1).getCount(), "已放入的 6 个保留");
    }

    // ── consume ────────────────────────────────────────────────

    @Test
    @DisplayName("consume 正常扣 N，返回实际扣减量")
    void consumeDeductsRequested() {
        ContainerContext ctx = newCtx(true, 2, null);
        ctx.setItem(0, new ItemStack(Items.COAL, 8));
        assertEquals(3, ctx.consume(0, 3));
        assertEquals(5, stackOf(ctx, 0).getCount());
    }

    @Test
    @DisplayName("consume 货少于请求 ⇒ 扣光（返回实际量）")
    void consumeTakesAllWhenShort() {
        ContainerContext ctx = newCtx(true, 2, null);
        ctx.setItem(0, new ItemStack(Items.COAL, 2));
        assertEquals(2, ctx.consume(0, 3));
        assertTrue(stackOf(ctx, 0).isEmpty());
    }

    @Test
    @DisplayName("I-C12 抽取权限：consume 不私吞「只出不进」槽里的货")
    void consumeRespectsExtractPermission() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.COAL, 8);
        h.noExtractSlots.add(0);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(0, ctx.consume(0, 3), "抽取被拒 ⇒ 0");
        assertEquals(8, stackOf(ctx, 0).getCount(), "源原样");
    }

    @Test
    @DisplayName("consume 写回静默失败 ⇒ 0 + 槽原样（按「没扣到」处理，调用方本拍不推进）")
    void consumeTreatsWriteFailureAsNothingConsumed() {
        SilentWriteHandler h = new SilentWriteHandler(64, 64);
        h.slots[0] = new ItemStack(Items.COAL, 8);
        h.silentSlots.add(0);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(0, ctx.consume(0, 3));
        assertEquals(8, stackOf(ctx, 0).getCount(), "扣减不成立 ⇒ 恢复原状");
    }

    @Test
    @DisplayName("consume：setter 抽干旧活引用后重定向，仍按独立快照恢复原物品与组件")
    void consumeRestoresSnapshotWhenSetterDrainsOldReference() {
        LimitedHandler h = new LimitedHandler(64) {
            boolean first = true;
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                if (first) {
                    first = false;
                    slots[slot].setCount(0);
                    slots[slot] = new ItemStack(Items.GOLD_INGOT, 1);
                } else {
                    super.setStackInSlot(slot, stack);
                }
            }
        };
        ItemStack original = new ItemStack(Items.COAL, 8);
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "before");
        original.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        ItemStack snapshot = original.copy();
        h.slots[0] = original;
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(0, ctx.consume(0, 3));
        assertTrue(original.isEmpty(), "旧活引用确实被 setter 抽干");
        assertTrue(ItemStack.matches(snapshot, ctx.getItem(0)), "恢复独立快照，不能恢复空掉的旧引用");
    }

    @Test
    @DisplayName("consume：setter 污染传入 expected，不得把坏写入判断为成功")
    void consumeDoesNotAcceptMutatedWriteArgument() {
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            boolean first = true;
            @Override public void setItem(int slot, ItemStack stack) {
                if (first) {
                    first = false;
                    stack.setCount(2);
                }
                super.setItem(slot, stack);
            }
        };
        ctx.set(0, new ItemStack(Items.COAL, 8));

        assertEquals(0, ctx.consume(0, 3), "实余2不是预期5，不能确认扣3");
        assertEquals(8, ctx.getItem(0).getCount(), "失败恢复操作前快照");
    }

    @Test
    @DisplayName("rollback：setter 污染参数不改变调用方快照，校验仍比较未暴露的原值")
    void rollbackDoesNotExposeCallerSnapshotToSetter() {
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            @Override public void setItem(int slot, ItemStack stack) {
                stack.setCount(1);
                super.setItem(slot, stack);
            }
        };
        ItemStack expected = new ItemStack(Items.COAL, 8);

        ContainerContext.rollbackSlot(ctx, 0, expected, "test argument mutation");

        assertEquals(8, expected.getCount(), "调用方快照不得污染");
        assertEquals(1, ctx.getItem(0).getCount(), "违约后端没有被伪装成恢复成功");
    }

    @Test
    @DisplayName("consume 抽插回退：抽取修改旧活引用时，独立抽前数量不得误判不完整")
    void consumeFallbackKeepsIndependentExtractionSnapshot() {
        PlainHandlerLike h = new PlainHandlerLike(1);
        h.slots[0] = new ItemStack(Items.COAL, 8);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(3, ctx.consume(0, 3));
        assertEquals(5, ctx.getItem(0).getCount(), "回退抽完整8后写余5，而非重插原8");
    }

    // ── transform ──────────────────────────────────────────────

    @ParameterizedTest(name = "{0}：transform 整槽替换成功（岩浆桶 → 空桶）")
    @ValueSource(booleans = {true, false})
    void transformReplacesSlot(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.LAVA_BUCKET));
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        boolean ok = ctx.transform(0, ItemStack::getCraftingRemainingItem);

        assertTrue(ok);
        assertTrue(stackOf(ctx, 0).is(Items.BUCKET), "残留物落槽");
        assertEquals(1, stackOf(ctx, 0).getCount());
        assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
    }

    @ParameterizedTest(name = "exactWrite={0}：transform 无变化仍 true，零写入、零通知")
    @ValueSource(booleans = {true, false})
    void transformNoChangeSkipsWrite(boolean exactWrite) {
        FakeContainerContext ctx = new FakeContainerContext(2, 9) {
            @Override public boolean supportsExactWrite(int slot) { return exactWrite; }
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                throw new AssertionError("无变化不得调用精确 setter");
            }
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("无变化不得调用数量 setter");
            }
        };
        ctx.set(0, new ItemStack(Items.REDSTONE, 5));
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        assertTrue(ctx.transform(0, s -> s.copyWithCount(5)));
        assertTrue(ctx.transform(1, s -> s), "空槽无变化同样成功");

        assertTrue(ctx.syncedSlots.isEmpty(), "内容没变就不写不同步");
        assertEquals(revision, ContainerLivingItemHandler.getContainerRevision(ctx));
        assertEquals(5, ctx.getItem(0).getCount());
    }

    @Test
    @DisplayName("transform 写失败 ⇒ false + 槽原样（不可拆整槽：要么成要么不动）")
    void transformRestoresOnWriteFailure() {
        SilentWriteHandler h = new SilentWriteHandler(64, 64);
        h.slots[0] = new ItemStack(Items.LAVA_BUCKET);
        h.silentSlots.add(0);
        SimpleContainerContext ctx = new SimpleContainerContext(h) {
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("transform 不得走数量 setter");
            }
            @Override public void onItemComponentsChanged(int slot, ItemStack stack) {
                throw new AssertionError("静默未写入，原槽没变，不应通知");
            }
        };
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        assertFalse(ctx.transform(0, ItemStack::getCraftingRemainingItem));
        assertTrue(stackOf(ctx, 0).is(Items.LAVA_BUCKET), "还原成功 ⇒ 原样");
        assertEquals(1, stackOf(ctx, 0).getCount());
        assertEquals(revision, ContainerLivingItemHandler.getContainerRevision(ctx));
    }

    @Test
    @DisplayName("transform：setter 抽干旧活引用后重定向，仍恢复整堆原物品与组件")
    void transformRestoresSnapshotWhenSetterDrainsOldReference() {
        LimitedHandler h = new LimitedHandler(64) {
            boolean first = true;
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                if (first) {
                    first = false;
                    slots[slot].setCount(0);
                    slots[slot] = new ItemStack(Items.GOLD_INGOT, 1);
                } else {
                    super.setStackInSlot(slot, stack);
                }
            }
        };
        ItemStack original = new ItemStack(Items.OAK_LOG, 8);
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "before");
        original.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        ItemStack snapshot = original.copy();
        h.slots[0] = original;
        SimpleContainerContext ctx = new SimpleContainerContext(h) {
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("正写与恢复均不得使用数量 setter");
            }
        };

        assertFalse(ctx.transform(0, s -> new ItemStack(Items.STRIPPED_OAK_LOG, s.getCount())));
        assertTrue(original.isEmpty(), "旧活引用确实被 setter 抽干");
        assertTrue(ItemStack.matches(snapshot, ctx.getItem(0)), "恢复原物品、整堆数量与组件，而非空掉的旧引用");
    }

    @Test
    @DisplayName("transform：setter 污染传入结果，不得把部分落地误判为整堆变换成功")
    void transformDoesNotAcceptMutatedWriteArgument() {
        var ctx = new FakeContainerContext(1, 1) {
            int writes;
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                if (++writes == 1) stack.setCount(2);
                return super.trySetItemExact(slot, stack);
            }
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("正写与恢复均不得使用数量 setter");
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 8);
        ctx.set(0, before.copy());
        ItemStack expected = new ItemStack(Items.STRIPPED_OAK_LOG, 8);

        assertFalse(ctx.transform(0, s -> expected), "实收2不是预期8，不能确认整堆变换");
        assertEquals(8, expected.getCount(), "fn 返回值不得被 setter 污染");
        assertEquals(2, ctx.writes, "精确正写失败后仅尝试一次精确恢复");
        assertTrue(ItemStack.matches(before, ctx.getItem(0)), "失败恢复原物品与整堆数量");
        assertEquals(java.util.List.of(0), ctx.syncedSlots, "失败只同步恢复后的槽状态");
    }

    @Test
    @DisplayName("transform：UOE setter + 会跨槽重定向的 insert，禁止真实抽插且两槽原样")
    void transformUnsupportedSetterNeverFallsBackToRedirectingInsert() {
        var h = new LimitedHandler(64, 64) {
            int writes;
            int realExtracts;
            int realInserts;
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                writes++;
                throw new UnsupportedOperationException("只开放传输");
            }
            @Override public ItemStack extractItem(int slot, int count, boolean simulate) {
                if (!simulate) realExtracts++;
                return super.extractItem(slot, count, simulate);
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (!simulate) {
                    realInserts++;
                    slots[1] = stack.copy();
                }
                return ItemStack.EMPTY;
            }
        };
        ItemStack source = new ItemStack(Items.OAK_LOG, 8);
        ItemStack other = new ItemStack(Items.GOLD_INGOT, 3);
        h.slots[0] = source.copy();
        h.slots[1] = other.copy();
        SimpleContainerContext ctx = new SimpleContainerContext(h) {
            @Override public void onItemComponentsChanged(int slot, ItemStack stack) {
                throw new AssertionError("未发生变动，不应通知");
            }
        };
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        assertTrue(ctx.supportsExactWrite(0), "声明 Modifiable 不等于 setter 一定可用");
        assertFalse(ctx.transform(0, s -> new ItemStack(Items.STRIPPED_OAK_LOG, s.getCount())));

        assertEquals(1, h.writes);
        assertEquals(0, h.realExtracts, "不调用 extract(false)");
        assertEquals(0, h.realInserts, "不调用可能跨槽写入的 insert(false)");
        assertTrue(ItemStack.matches(source, h.slots[0]));
        assertTrue(ItemStack.matches(other, h.slots[1]));
        assertEquals(revision, ContainerLivingItemHandler.getContainerRevision(ctx));
    }

    @Test
    @DisplayName("transform：无精确能力的只读后端在 setter 前拒绝，fn 也不能污染源")
    void transformRejectsBackendWithoutExactWrite() {
        PlainHandlerLike h = new PlainHandlerLike(2) {
            @Override public ItemStack extractItem(int slot, int count, boolean simulate) {
                throw new AssertionError("无精确能力不得抽取");
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                throw new AssertionError("无精确能力不得插入");
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 8);
        h.slots[0] = before.copy();
        h.slots[1] = new ItemStack(Items.GOLD_INGOT, 3);
        ItemStack other = h.slots[1].copy();
        SimpleContainerContext ctx = new SimpleContainerContext(h) {
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                throw new AssertionError("必须先检查精确能力");
            }
            @Override public void onItemComponentsChanged(int slot, ItemStack stack) {
                throw new AssertionError("无精确能力不得发布变更");
            }
        };

        assertFalse(ctx.supportsExactWrite(0));
        assertFalse(ctx.transform(0, s -> {
            s.setCount(1);
            return new ItemStack(Items.STRIPPED_OAK_LOG, 8);
        }));
        assertTrue(ctx.transform(0, ItemStack::copy), "无变化仍 true，即使只读");
        assertTrue(ItemStack.matches(before, h.slots[0]));
        assertTrue(ItemStack.matches(other, h.slots[1]));
        assertFalse(ctx.transform(-1, s -> { throw new AssertionError("越界不得调用 fn"); }));
        assertFalse(ctx.transform(2, s -> { throw new AssertionError("越界不得调用 fn"); }));
    }

    @ParameterizedTest(name = "writesBeforeFalse={0}：setter false 不能被匹配回读覆写为成功")
    @ValueSource(booleans = {false, true})
    void transformFalseSetterCannotSucceedEvenWithMatchingReadback(boolean writesBeforeFalse) {
        var ctx = new FakeContainerContext(1, 1) {
            int writes;
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                writes++;
                if (writesBeforeFalse) super.trySetItemExact(slot, stack);
                return false;
            }
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("拒写不得转数量 setter");
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 8);
        ctx.set(0, before.copy());

        assertFalse(ctx.transform(0, s -> new ItemStack(Items.STRIPPED_OAK_LOG, s.getCount())));

        assertEquals(writesBeforeFalse ? 2 : 1, ctx.writes);
        assertTrue(ItemStack.matches(before, ctx.getItem(0)));
        assertEquals(writesBeforeFalse ? List.of(0) : List.of(), ctx.syncedSlots);
    }

    @ParameterizedTest(name = "partialWrite={0}：setter 异常隔离，回读后只精确恢复")
    @ValueSource(booleans = {false, true})
    void transformSetterExceptionReadsBackAndRestores(boolean partialWrite) {
        var h = new LimitedHandler(64) {
            int writes;
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                if (++writes == 1) {
                    if (partialWrite) slots[slot] = stack.copyWithCount(2);
                    throw new IllegalStateException("setter 异常");
                }
                super.setStackInSlot(slot, stack);
            }
        };
        List<ItemStack> synced = new ArrayList<>();
        SimpleContainerContext ctx = new SimpleContainerContext(h) {
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("恢复不得走数量 setter");
            }
            @Override public void syncSlotToClients(int slot, ItemStack stack) {
                synced.add(stack.copy());
                super.syncSlotToClients(slot, stack);
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 8);
        h.slots[0] = before.copy();
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        assertFalse(ctx.transform(0, s -> new ItemStack(Items.STRIPPED_OAK_LOG, s.getCount())));

        assertEquals(partialWrite ? 2 : 1, h.writes);
        assertTrue(ItemStack.matches(before, ctx.getItem(0)));
        assertEquals(partialWrite ? 1 : 0, synced.size());
        if (partialWrite) assertTrue(ItemStack.matches(before, synced.getFirst()));
        assertEquals(revision + (partialWrite ? 1 : 0), ContainerLivingItemHandler.getContainerRevision(ctx));
    }

    @Test
    @DisplayName("transform：恢复 setter 污染参数并抛异常，不能假报恢复，只通知最终实值")
    void transformFailedRestoreSyncsActualStateAndKeepsSnapshotIndependent() {
        List<ItemStack> synced = new ArrayList<>();
        List<ItemStack> requested = new ArrayList<>();
        var ctx = new FakeContainerContext(1, 1) {
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                requested.add(stack.copy());
                stack.setCount(2);
                super.trySetItemExact(slot, stack);
                if (requested.size() == 2) throw new IllegalStateException("恢复失败");
                return true;
            }
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("恢复异常也不得抽插回退");
            }
            @Override public void syncSlotToClients(int slot, ItemStack stack) {
                synced.add(stack.copy());
                super.syncSlotToClients(slot, stack);
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 8);
        ItemStack expected = new ItemStack(Items.STRIPPED_OAK_LOG, 8);
        ctx.set(0, before.copy());

        assertFalse(ctx.transform(0, s -> expected));

        assertEquals(2, requested.size(), "恢复只尝试一次");
        assertTrue(ItemStack.matches(expected, requested.get(0)));
        assertTrue(ItemStack.matches(before, requested.get(1)), "恢复请求保留原数量与原型");
        assertEquals(8, before.getCount());
        assertEquals(8, expected.getCount());
        assertTrue(ItemStack.matches(before.copyWithCount(2), ctx.getItem(0)));
        assertEquals(List.of(0), ctx.syncedSlots);
        assertEquals(1, synced.size());
        assertTrue(ItemStack.matches(ctx.getItem(0), synced.getFirst()));
    }

    @Test
    @DisplayName("transform：期望快照独立于 fn 保留的返回引用")
    void transformCopiesExpectedBeforeCallingSetter() {
        ItemStack returned = new ItemStack(Items.STRIPPED_OAK_LOG, 8);
        FakeContainerContext ctx = new FakeContainerContext(1, 1) {
            boolean first = true;
            @Override public boolean trySetItemExact(int slot, ItemStack stack) {
                if (first) {
                    first = false;
                    returned.setCount(2);
                    stack.setCount(2);
                }
                return super.trySetItemExact(slot, stack);
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 8);
        ctx.set(0, before.copy());

        assertFalse(ctx.transform(0, s -> returned), "不能跟随外部引用把期望8改成2");
        assertEquals(2, returned.getCount(), "接缝确实修改了 fn 保留的外部引用");
        assertTrue(ItemStack.matches(before, ctx.getItem(0)));
        assertEquals(List.of(0), ctx.syncedSlots);
    }

    @ParameterizedTest(name = "clampFirstWrite={0}：整堆正写与恢复均不截断，通知链恰好一次")
    @ValueSource(booleans = {false, true})
    void transformDoesNotClampAndNotifiesDirtyRevisionAndSlotOnce(boolean clampFirstWrite) {
        var h = new LimitedHandler(64) {
            int writes;
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                super.setStackInSlot(slot, ++writes == 1 && clampFirstWrite
                    ? stack.copyWithCount(1) : stack);
            }
        };
        ItemStack before = new ItemStack(Items.OAK_LOG, 2048);
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "before");
        before.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        h.slots[0] = before.copy();
        ItemStack expected = new ItemStack(Items.STRIPPED_OAK_LOG, 2048);
        CompoundTag resultTag = new CompoundTag();
        resultTag.putString("marker", "after");
        expected.set(DataComponents.CUSTOM_DATA, CustomData.of(resultTag));
        BlockEntity first = mock(BlockEntity.class);
        BlockEntity second = mock(BlockEntity.class);
        List<ItemStack> synced = new ArrayList<>();
        SimpleContainerContext ctx = new SimpleContainerContext(h,
            List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), List.of(first, second)) {
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("整槽变换不得借道会截断的 setItem");
            }
            @Override public void syncSlotToClients(int slot, ItemStack stack) {
                synced.add(stack.copy());
                super.syncSlotToClients(slot, stack);
            }
        };
        TickContext tick = new TickContext(ctx);
        try (var hooks = mockStatic(ContainerTickHooks.class)) {
            ctx.setTickContext(tick);
            hooks.verify(() -> ContainerTickHooks.fireTickStart(ctx, tick));
        }
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        assertEquals(!clampFirstWrite, ctx.transform(0, s -> expected));

        ItemStack finalStack = clampFirstWrite ? before : expected;
        assertTrue(ItemStack.matches(finalStack, ctx.getItem(0)), "数量、原型及全部组件完整落地");
        assertEquals(2048, ctx.getItem(0).getCount(), "报告上限64不得裁剪已有整堆");
        assertNotSame(expected, ctx.getItem(0));
        assertEquals(clampFirstWrite ? 2 : 1, h.writes);
        assertEquals(1, synced.size());
        assertTrue(ItemStack.matches(finalStack, synced.getFirst()));
        assertEquals(Set.of(0), tick.dirtySlots);
        assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
        assertTrue(ctx.transform(0, ItemStack::copy));
        assertEquals(1, synced.size(), "随后 no-op 不再通知");
        assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
        verify(first, times(1)).setChanged();
        verify(second, times(1)).setChanged();
        ctx.setTickContext(null);
    }

    @Test
    @DisplayName("transform：掉落物精确写回整堆，走实体标脏与同步，bump 恰好一次")
    void transformItemEntityWritesAndSyncsNormally() {
        ItemStack before = new ItemStack(Items.OAK_LOG, 128);
        ItemStack expected = new ItemStack(Items.STRIPPED_OAK_LOG, 128);
        AtomicReference<ItemStack> actual = new AtomicReference<>(before.copy());
        ItemEntity entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(17);
        when(entity.getItem()).thenAnswer(call -> actual.get());
        List<ItemStack> writes = new ArrayList<>();
        doAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            actual.set(stack);
            writes.add(stack.copy());
            return null;
        }).when(entity).setItem(any(ItemStack.class));
        SynchedEntityData data = mock(SynchedEntityData.class);
        when(entity.getEntityData()).thenReturn(data);
        List<SynchedEntityData.DataValue<?>> packed = List.of(
            new SynchedEntityData.DataValue<>(0, EntityDataSerializers.ITEM_STACK, expected.copy()));
        when(data.packDirty()).thenReturn(packed);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        ItemEntityContainerContext ctx = new ItemEntityContainerContext(entity, level) {
            @Override public void setItem(int slot, ItemStack stack) {
                throw new AssertionError("掉落物 transform 必须走精确入口");
            }
        };
        long revision = ContainerLivingItemHandler.getContainerRevision(ctx);

        assertTrue(ctx.supportsExactWrite(0));
        assertTrue(ctx.transform(0, s -> expected));
        assertTrue(ItemStack.matches(expected, actual.get()));
        assertNotSame(expected, actual.get());
        assertEquals(3, writes.size(), "精确写一次；实体同步先置空再写最终栈");
        assertTrue(ItemStack.matches(expected, writes.get(0)));
        assertTrue(writes.get(1).isEmpty(), "实体同步需强制标脏");
        assertTrue(ItemStack.matches(expected, writes.get(2)));
        assertEquals(128, expected.getCount());
        assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
        assertTrue(ctx.transform(0, ItemStack::copy));
        assertEquals(3, writes.size(), "no-op 不重写实体数据");
        assertEquals(revision + 1, ContainerLivingItemHandler.getContainerRevision(ctx));
        verify(data, times(1)).packDirty();
        verify(chunks, times(1)).broadcastAndSend(entity, new ClientboundSetEntityDataPacket(17, packed));
    }

    @Test
    @DisplayName("掉落物精确接缝：越界或已移除实体拒写，不碰实体同步")
    void itemEntityExactWriteRejectsInvalidSlotAndRemovedEntity() {
        ItemEntity entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        ItemEntityContainerContext ctx = new ItemEntityContainerContext(entity, mock(ServerLevel.class));
        ItemStack expected = new ItemStack(Items.OAK_LOG, 8);

        assertFalse(ctx.supportsExactWrite(-1));
        assertFalse(ctx.supportsExactWrite(1));
        assertFalse(ctx.trySetItemExact(-1, expected));
        assertFalse(ctx.trySetItemExact(1, expected));
        when(entity.isRemoved()).thenReturn(true);
        assertFalse(ctx.supportsExactWrite(0));
        assertFalse(ctx.trySetItemExact(0, expected));
        verify(entity, never()).setItem(any(ItemStack.class));
        verify(entity, never()).getEntityData();
    }

    // ── swap：双非空整栈互换（活魂符换位原语，2026-11-12） ──────────

    @ParameterizedTest(name = "{0}：双非空互换 —— 内容对调、moved=进入 to 的数量、总量守恒")
    @ValueSource(booleans = {true, false})
    void swapExchangesBothStacks(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 30));
        ctx.setItem(1, new ItemStack(Items.GOLD_INGOT, 2));

        MoveResult result = ctx.swap(0, 1);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(30, success.moved(), "moved = 移入 to 的数量（原 from 栈）");
        assertNull(success.undo(), "swap 的逆操作是再次 swap，无撤销凭据");
        assertEquals(2, stackOf(ctx, 0).getCount(), "target 栈来到 from");
        assertEquals(Items.GOLD_INGOT, stackOf(ctx, 0).getItem());
        assertEquals(30, stackOf(ctx, 1).getCount(), "source 栈来到 to");
        assertEquals(Items.REDSTONE, stackOf(ctx, 1).getItem());
        assertEquals(32, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("swap 成功登记两槽进 movedSlots（同拍旧条目失效语义与 moveStack 一致）")
    void swapRegistersMovedSlots() {
        SimpleContainerContext ctx = new SimpleContainerContext(new LimitedHandler(64, 64));
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 5));
        ctx.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));
        TickContext tick = new TickContext(ctx);
        ctx.setTickContext(tick);

        assertInstanceOf(MoveResult.Success.class, ctx.swap(0, 1));

        assertTrue(tick.movedSlots.contains(0), "from 侧登记");
        assertTrue(tick.movedSlots.contains(1), "to 侧登记");
    }

    @ParameterizedTest(name = "{0}：一侧为空 ⇒ SOURCE_EMPTY（一空一非空走 move，不是 swap 职责）")
    @ValueSource(booleans = {true, false})
    void swapRequiresBothNonEmpty(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 5));

        assertEquals(MoveResult.Reason.SOURCE_EMPTY,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(0, 1)).reason());
        assertEquals(MoveResult.Reason.SOURCE_EMPTY,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(1, 0)).reason());
        assertEquals(5, stackOf(ctx, 0).getCount(), "状态未变");
        assertTrue(stackOf(ctx, 1).isEmpty(), "状态未变");
    }

    @Test
    @DisplayName("swap：同槽 / 越界 ⇒ INVALID_ADDRESS")
    void swapRejectsInvalidAddress() {
        ContainerContext ctx = newCtx(true, 2, null);
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 1));
        ctx.setItem(1, new ItemStack(Items.GOLD_INGOT, 1));

        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(0, 0)).reason());
        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(0, 9)).reason());
    }

    @Test
    @DisplayName("swap 双向抽取权限：任一侧只出不进 ⇒ SOURCE_NOT_EXTRACTABLE，状态不变")
    void swapHonorsExtractionPermissionOnBothSides() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 8);
        h.slots[1] = new ItemStack(Items.GOLD_INGOT, 4);
        h.noExtractSlots.add(1);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(0, 1)).reason());
        assertEquals(8, stackOf(ctx, 0).getCount(), "状态未变");
        assertEquals(4, stackOf(ctx, 1).getCount(), "状态未变");
    }

    @ParameterizedTest(name = "{0}：整栈装不进对方槽（容量 512 → 64）⇒ TARGET_INSUFFICIENT，不部分交换")
    @ValueSource(booleans = {true, false})
    void swapRejectsWhenStackDoesNotFit(boolean handlerBacked) {
        ContainerContext ctx = newCtx(handlerBacked, 2, new int[]{64, 512});
        ctx.setItem(0, new ItemStack(Items.REDSTONE, 1));
        ctx.setItem(1, new ItemStack(Items.IRON_INGOT, 512));

        assertEquals(MoveResult.Reason.TARGET_INSUFFICIENT,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(0, 1)).reason());
        assertEquals(1, stackOf(ctx, 0).getCount(), "状态未变");
        assertEquals(512, stackOf(ctx, 1).getCount(), "状态未变（不截断、不部分交换）");
    }

    @Test
    @DisplayName("swap 准入拒绝（handler 拒收异类写入侧）⇒ TARGET_INSUFFICIENT，状态不变")
    void swapHonorsAdmissionOnBothSides() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 8);
        h.slots[1] = new ItemStack(Items.GOLD_INGOT, 4);
        h.noInsertSlots.add(1);   // 槽 1 拒绝接纳来自槽 0 的红石
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(MoveResult.Reason.TARGET_INSUFFICIENT,
            assertInstanceOf(MoveResult.Rejected.class, ctx.swap(0, 1)).reason());
        assertEquals(8, stackOf(ctx, 0).getCount(), "状态未变");
        assertEquals(4, stackOf(ctx, 1).getCount(), "状态未变");
    }

    @Test
    @DisplayName("swap to 侧静默写失败 ⇒ BackendViolation + 双槽恢复原状")
    void swapRollsBackOnSilentWrite() {
        SilentWriteHandler h = new SilentWriteHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 8);
        h.slots[1] = new ItemStack(Items.GOLD_INGOT, 4);
        h.silentSlots.add(1);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertInstanceOf(MoveResult.BackendViolation.class, ctx.swap(0, 1));
        assertEquals(8, stackOf(ctx, 0).getCount(), "from 恢复原状");
        assertEquals(4, stackOf(ctx, 1).getCount(), "to 恢复原状");
    }

    @Test
    @DisplayName("swap to 侧整库重定向 ⇒ BackendViolation + 双槽恢复原状")
    void swapRollsBackOnRedirect() {
        FirstWriteRedirectHandler h = new FirstWriteRedirectHandler(1, 64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 8);
        h.slots[1] = new ItemStack(Items.GOLD_INGOT, 4);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertInstanceOf(MoveResult.BackendViolation.class, ctx.swap(0, 1));
        assertEquals(8, stackOf(ctx, 0).getCount(), "from 恢复原状");
        assertEquals(Items.GOLD_INGOT, stackOf(ctx, 1).getItem(), "to 恢复原状");
        assertEquals(4, stackOf(ctx, 1).getCount());
    }

    @Test
    @DisplayName("swap 保全组件：双方栈的组件随物品完整互换")
    void swapPreservesComponents() {
        ContainerContext ctx = newCtx(true, 2, null);
        ItemStack named = new ItemStack(Items.REDSTONE, 5);
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "kept");
        named.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        ItemStack plain = new ItemStack(Items.GOLD_INGOT, 2);
        ctx.setItem(0, named);
        ctx.setItem(1, plain);

        assertInstanceOf(MoveResult.Success.class, ctx.swap(0, 1));

        ItemStack atFrom = stackOf(ctx, 0);
        assertEquals(Items.GOLD_INGOT, atFrom.getItem(), "target 栈来到 from");
        ItemStack atTo = stackOf(ctx, 1);
        assertEquals(Items.REDSTONE, atTo.getItem(), "source 栈来到 to");
        assertEquals("kept", atTo.get(DataComponents.CUSTOM_DATA).copyTag().getString("marker"),
            "source 栈组件原样随行");
    }
}
