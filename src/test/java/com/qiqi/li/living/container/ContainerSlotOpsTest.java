package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

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

    // ── transform ──────────────────────────────────────────────

    @Test
    @DisplayName("transform 整槽替换成功（岩浆桶 → 空桶，对齐原版残留物语义）")
    void transformReplacesSlot() {
        ContainerContext ctx = newCtx(true, 2, null);
        ctx.setItem(0, new ItemStack(Items.LAVA_BUCKET));

        boolean ok = ctx.transform(0, ItemStack::getCraftingRemainingItem);

        assertTrue(ok);
        assertTrue(stackOf(ctx, 0).is(Items.BUCKET), "残留物落槽");
        assertEquals(1, stackOf(ctx, 0).getCount());
    }

    @Test
    @DisplayName("transform fn 判定无变化 ⇒ true 且不写（无变化不触发同步）")
    void transformNoChangeSkipsWrite() {
        FakeContainerContext ctx = new FakeContainerContext(2, 9);
        ctx.set(0, new ItemStack(Items.REDSTONE, 5));
        int syncCallsBefore = ctx.syncedSlots.size();

        assertTrue(ctx.transform(0, s -> s.copyWithCount(5)));

        assertEquals(syncCallsBefore, ctx.syncedSlots.size(), "内容没变就不写不同步");
    }

    @Test
    @DisplayName("transform 写失败 ⇒ false + 槽原样（不可拆整槽：要么成要么不动）")
    void transformRestoresOnWriteFailure() {
        SilentWriteHandler h = new SilentWriteHandler(64, 64);
        h.slots[0] = new ItemStack(Items.LAVA_BUCKET);
        h.silentSlots.add(0);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertFalse(ctx.transform(0, ItemStack::getCraftingRemainingItem));
        assertTrue(stackOf(ctx, 0).is(Items.LAVA_BUCKET), "还原成功 ⇒ 原样");
        assertEquals(1, stackOf(ctx, 0).getCount());
    }
}
