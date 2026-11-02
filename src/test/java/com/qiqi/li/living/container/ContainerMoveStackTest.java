package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 一步整栈移动（{@code I-C12}）与安全撤销。
 *
 * <p>守四件事：</p>
 * <ol>
 *   <li><b>守恒</b>：移动前后总量不变（源清空 + 目标收货，不是「先写后清」）；</li>
 *   <li><b>整栈不部分</b>：超大栈放进小槽 ⇒ 拒绝，不是搬一部分（那会变成漏斗压送语义）；</li>
 *   <li><b>撤销是两槽一起</b>：只恢复源会复制（活塞侧 2026-11-03 指出）；</li>
 *   <li><b>撤销前校验</b>：状态被第三方改动时拒绝执行，不覆盖。</li>
 * </ol>
 */
class ContainerMoveStackTest {

    /**
     * 槽位上限按槽可配置的 handler（真实 IItemHandler 语义，setItem 走 setStackInSlot）。
     *
     * <p>⭐ {@code oversize} 决定它是哪种容器 —— 这个区别是本测试的核心，不是细节：</p>
     * <ul>
     *   <li><b>false</b>（普通容器）：{@code insertItem} 用 {@code min(slotLimit, 物品上限)}，
     *       与 NeoForge {@code ItemStackHandler} 一致 ⇒ 500 个只能进 64 个。</li>
     *   <li><b>true</b>（抽屉 / 堆叠升级）：槽上限是权威、不看物品上限 ⇒ 500 个能进 512 槽。</li>
     * </ul>
     * <p>⚠️ 2026-11-03 实测踩到：一开始全用 false，导致「超大栈整栈移动」被拒 ——
     * 那不是实现 bug，是 <b>{@code canAcceptAll} 忠实反映了容器意愿</b>。
     * 两种容器都要测，才知道接口在真实差异下是否成立。</p>
     */
    private static class LimitedHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;
        final int[] limits;
        final boolean oversize;
        /** 只出不进的槽（{@code extractItem} 恒 EMPTY）—— 用于钉住 I-C12 的「抽取权限」维度。 */
        final java.util.Set<Integer> noExtractSlots = new java.util.HashSet<>();

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
            if (existing.isEmpty()) {
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
            if (noExtractSlots.contains(slot)) return ItemStack.EMPTY;   // 只出不进的槽
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

    /**
     * 只实现 {@code IItemHandler}（<b>不</b>实现 {@code Modifiable}）—— 没有 {@code setStackInSlot}。
     *
     * <p>用于钉住 {@code Unsupported} 与 {@code BackendViolation} 的区分：
     * 前者是「重试永远不会成功」，后者是「说能写却没写对」。混在一起会让排查走错方向。</p>
     */
    private static class PlainHandler implements net.neoforged.neoforge.items.IItemHandler {
        final ItemStack[] slots;

        PlainHandler(int size) {
            this.slots = new ItemStack[size];
            for (int i = 0; i < size; i++) slots[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) { return slots[slot]; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack existing = slots[slot];
            if (existing.isEmpty()) {
                int toInsert = Math.min(stack.getCount(), 64);
                if (!simulate) slots[slot] = stack.copyWithCount(toInsert);
                ItemStack rest = stack.copy();
                rest.shrink(toInsert);
                return rest.isEmpty() ? ItemStack.EMPTY : rest;
            }
            return stack;
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

    private static int total(SimpleContainerContext ctx) {
        int sum = 0;
        for (int i = 0; i < ctx.getSize(); i++) sum += ctx.getItem(i).getCount();
        return sum;
    }

    // ── 成功路径 ────────────────────────────────────────────────

    @Test
    @DisplayName("整栈移动：源清空、目标收货、总量守恒")
    void movesWholeStack() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.moveStack(0, 1);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(32, success.moved(), "moved 恒等于操作开始时的源栈数量");
        assertTrue(ctx.getItem(0).isEmpty(), "源槽必须清空");
        assertEquals(32, ctx.getItem(1).getCount(), "目标槽收货");
        assertEquals(32, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("超大栈整栈移动：500 个进 512 上限的槽，一次搬完（不拆）")
    void movesOversizedStackInOneGo() {
        LimitedHandler h = new LimitedHandler(true, 512, 512);
        h.slots[0] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.moveStack(0, 1);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, result);
        assertEquals(500, success.moved(), "超大栈也是整栈，不分批");
        assertEquals(500, ctx.getItem(1).getCount());
        assertEquals(500, total(ctx));
    }

    // ── 拒绝路径 ────────────────────────────────────────────────

    @Test
    @DisplayName("目标被占 ⇒ 拒绝（纯位移不合并）")
    void rejectsOccupiedTarget() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        h.slots[1] = new ItemStack(Items.GOLD_INGOT, 3);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.moveStack(0, 1);

        MoveResult.Rejected rejected = assertInstanceOf(MoveResult.Rejected.class, result);
        assertEquals(MoveResult.Reason.TARGET_OCCUPIED, rejected.reason());
        assertEquals(32, ctx.getItem(0).getCount(), "状态未变");
        assertEquals(Items.GOLD_INGOT, ctx.getItem(1).getItem(), "状态未变");
    }

    @Test
    @DisplayName("源为空 ⇒ 拒绝")
    void rejectsEmptySource() {
        SimpleContainerContext ctx = new SimpleContainerContext(new LimitedHandler(64, 64));
        MoveResult result = ctx.moveStack(0, 1);
        assertEquals(MoveResult.Reason.SOURCE_EMPTY,
            assertInstanceOf(MoveResult.Rejected.class, result).reason());
    }

    @Test
    @DisplayName("整栈放不进目标 ⇒ 拒绝，不搬一部分（部分交付属于压送语义）")
    void rejectsWhenTargetCannotHoldWholeStack() {
        LimitedHandler h = new LimitedHandler(true, 512, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.moveStack(0, 1);

        assertEquals(MoveResult.Reason.TARGET_INSUFFICIENT,
            assertInstanceOf(MoveResult.Rejected.class, result).reason());
        assertEquals(500, ctx.getItem(0).getCount(), "一个都没搬走");
        assertTrue(ctx.getItem(1).isEmpty(), "目标没收到东西");
        assertEquals(500, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("I-C12 抽取权限：源槽「只出不进」⇒ SOURCE_NOT_EXTRACTABLE（容量与准入都通过也不搬）")
    void rejectsUnextractableSource() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        h.noExtractSlots.add(0);          // 有货、装得下、准入通过 —— 但不允许抽取
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.moveStack(0, 1);

        assertEquals(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE,
            assertInstanceOf(MoveResult.Rejected.class, result).reason(),
            "只查容量与准入不够：抽取权限是第三维");
        assertEquals(32, ctx.getItem(0).getCount(), "源槽一个都没少");
        assertTrue(ctx.getItem(1).isEmpty(), "目标没收到东西");
    }

    @Test
    @DisplayName("后端不支持精确写入 ⇒ Unsupported（不是 BackendViolation）")
    void reportsUnsupportedWhenNoExactWrite() {
        PlainHandler h = new PlainHandler(2);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult result = ctx.moveStack(0, 1);

        assertEquals(MoveResult.Reason.EXACT_WRITE_UNSUPPORTED,
            assertInstanceOf(MoveResult.Unsupported.class, result).reason(),
            "无 setStackInSlot ⇒ 重试永远不会成功，不能说成「后端违约」");
        assertEquals(32, ctx.getItem(0).getCount(), "状态未变");
    }

    @Test
    @DisplayName("源目标相同 / 越界 ⇒ INVALID_ADDRESS")
    void rejectsInvalidAddress() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 1);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.moveStack(0, 0)).reason());
        assertEquals(MoveResult.Reason.INVALID_ADDRESS,
            assertInstanceOf(MoveResult.Rejected.class, ctx.moveStack(0, 9)).reason());
    }

    // ── 撤销 ────────────────────────────────────────────────────

    @Test
    @DisplayName("撤销：两槽一起恢复（只恢复源会复制）")
    void undoRestoresBothSlots() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, ctx.moveStack(0, 1));
        assertTrue(ctx.undo(success.undo()), "撤销应成功");

        assertEquals(32, ctx.getItem(0).getCount(), "源恢复");
        assertTrue(ctx.getItem(1).isEmpty(), "目标清空 —— 否则两槽各有 32 = 复制");
        assertEquals(32, total(ctx), "总量守恒");
    }

    @Test
    @DisplayName("撤销前校验：目标被第三方改动 ⇒ 拒绝执行，不覆盖")
    void undoRefusesWhenStateChanged() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 32);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, ctx.moveStack(0, 1));

        // 模拟第三方（setter 监听器 / 玩家）改动了目标槽
        ctx.setItem(1, new ItemStack(Items.GOLD_INGOT, 7));

        assertFalse(ctx.undo(success.undo()), "状态已变 ⇒ 不得覆盖");
        assertEquals(Items.GOLD_INGOT, ctx.getItem(1).getItem(), "第三方改动被保留");
    }

    @Test
    @DisplayName("撤销后总量守恒（撤销不是第二次移动）")
    void undoKeepsTotalInvariant() {
        LimitedHandler h = new LimitedHandler(true, 512, 512);
        h.slots[0] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        int before = total(ctx);
        MoveResult.Success success = assertInstanceOf(MoveResult.Success.class, ctx.moveStack(0, 1));
        assertEquals(before, total(ctx), "移动后守恒");
        assertTrue(ctx.undo(success.undo()));
        assertEquals(before, total(ctx), "撤销后仍守恒");
    }

    // ── 缝 3：tick 内快照陈旧（2026-11-03）────────────────────────

    @Test
    @DisplayName("缝 3：本拍移动后快照必须重建（否则红石/流体/应力按旧布局跑一拍）")
    void snapshotRebuiltAfterMoveInSameTick() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 1);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        TickContext tick = new TickContext(ctx);
        ContainerSnapshot before = tick.getSnapshot();   // 首次：惰性建 entry，revision=0

        assertInstanceOf(MoveResult.Success.class, ctx.moveStack(0, 1));

        ContainerSnapshot after = tick.getSnapshot();
        assertNotSame(before, after,
            "移动后本拍必须重建 —— 原 snapshotBuilt 首建后不再看修订号，"
                + "把 getCachedSnapshot 本就正确的按-revision 重建遮住了");
    }

    @Test
    @DisplayName("缝 3 反面：无变化时复用同一实例（不是每拍重建，稳态零成本）")
    void snapshotReusedWhenNothingChanged() {
        LimitedHandler h = new LimitedHandler(64, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 1);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        TickContext tick = new TickContext(ctx);
        ContainerSnapshot first = tick.getSnapshot();
        ContainerSnapshot second = tick.getSnapshot();

        assertSame(first, second, "修订号没变就不该重建");
    }

    // ── 预检 ────────────────────────────────────────────────────

    @Test
    @DisplayName("canAcceptAll 不改状态，只回答整栈能不能进")
    void canAcceptAllIsPureCheck() {
        LimitedHandler h = new LimitedHandler(true, 512, 64);
        h.slots[0] = new ItemStack(Items.REDSTONE, 500);
        SimpleContainerContext ctx = new SimpleContainerContext(h);

        assertFalse(ctx.canAcceptAll(1, ctx.getItem(0)), "500 个放不进上限 64 的槽");
        assertTrue(ctx.canAcceptAll(1, new ItemStack(Items.REDSTONE, 64)), "64 个可以");
        assertEquals(500, ctx.getItem(0).getCount(), "预检不改源");
        assertTrue(ctx.getItem(1).isEmpty(), "预检不改目标");
    }
}
