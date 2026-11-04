package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link PlainSlotAccessor} 的回滚契约：**只回原槽；装不下/被占就报告，不找其它槽、不落地**。
 *
 * <p>2026-11-05 扩：I-C12 写后回读（写回静默失败 ⇒ restore + 返回 EMPTY/0，不复制）
 * 与 I-C14 容量问容器（超堆叠槽合并 / 回滚不再被物品上限判满停摆）。</p>
 */
class PlainSlotAccessorTest {

    private static PlainSlotAccessor accessor(FakeContainerContext ctx) {
        return new PlainSlotAccessor(ctx, 0, new HashSet<>());
    }

    /** setItem 静默失败的替身：写入被无声丢弃（复刻第三方容器 setStackInSlot 违约）。 */
    private static class WriteIgnoringContext extends FakeContainerContext {
        WriteIgnoringContext(int[] limits) {
            super(limits.length, limits.length);
            withSlotLimits(limits);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            // 静默违约：写入直接丢弃
        }
    }

    @Test
    @DisplayName("回滚空槽:原槽为空 ⇒ 放回")
    void rollback_emptySlot_restores() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9);

        accessor(ctx).rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(2, ctx.getItem(0).getCount());
    }

    @Test
    @DisplayName("回滚同类:原槽有同类且有余量 ⇒ 合并")
    void rollback_sameItem_merges() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9);
        ctx.set(0, new ItemStack(Items.DIAMOND, 1));

        accessor(ctx).rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(3, ctx.getItem(0).getCount());
    }

    @Test
    @DisplayName("回滚异物品:原槽被占 ⇒ 只报告,不写其它槽")
    void rollback_differentItem_reportsAndDoesNotWriteElsewhere() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9);
        ctx.set(0, new ItemStack(Items.STONE, 1));

        accessor(ctx).rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(1, ctx.getItem(0).getCount(), "原槽不动");
        for (int i = 1; i < 9; i++) {
            assertTrue(ctx.getItem(i).isEmpty(), "不得把物品塞进其它槽(只报告日志)");
        }
    }

    @Test
    @DisplayName("插入同物品不同组件:不得合并,模拟与真实均拒绝")
    void insert_sameItemDifferentComponents_doesNotMerge() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9);
        ItemStack existing = new ItemStack(Items.DIAMOND, 2);
        existing.set(DataComponents.CUSTOM_NAME, Component.literal("kept apart"));
        ctx.set(0, existing);
        ItemStack incoming = new ItemStack(Items.DIAMOND, 3);
        PlainSlotAccessor accessor = accessor(ctx);

        assertEquals(0, accessor.simulateInsert(incoming));
        assertEquals(0, accessor.insert(incoming));

        assertEquals(2, ctx.getItem(0).getCount());
        assertEquals("kept apart", ctx.getItem(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(3, incoming.getCount(), "拒绝合并时来栈不得变化");
    }

    @Test
    @DisplayName("回滚满槽:只报告,不溢出到其它槽")
    void rollback_fullSlot_reportsAndDoesNotOverflow() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9);
        ctx.set(0, new ItemStack(Items.DIAMOND, 64));

        accessor(ctx).rollback(new ItemStack(Items.DIAMOND, 2));

        assertEquals(64, ctx.getItem(0).getCount());
        for (int i = 1; i < 9; i++) {
            assertTrue(ctx.getItem(i).isEmpty(), "不得写入其它槽(只报告日志)");
        }
    }

    // ===== I-C14 容量问容器（2026-11-05）=====

    @Test
    @DisplayName("超堆叠槽合并:已有 100(上限 512) ⇒ 能进 64,不再被物品上限判满停摆")
    void insert_superstackSlot_mergesBeyondItemMaxStackSize() {
        FakeContainerContext ctx = new FakeContainerContext(1, 1).withSlotLimits(new int[]{512});
        ctx.set(0, new ItemStack(Items.REDSTONE, 100));
        ItemStack incoming = new ItemStack(Items.REDSTONE, 64);

        assertEquals(64, accessor(ctx).insert(incoming), "旧 min(slotLimit, maxStackSize) 口径下这里是 0");
        assertEquals(164, ctx.getItem(0).getCount());
        assertEquals(0, incoming.getCount(), "全额收下 ⇒ 来栈清空");
    }

    @Test
    @DisplayName("普通槽物品上限 16(末影珍珠)⇒ 仍按 16 钳制,不因去 min 而过填")
    void insert_respectsItemMaxStackSize_onNormalSlot() {
        FakeContainerContext ctx = new FakeContainerContext(9, 9);
        ctx.set(0, new ItemStack(Items.ENDER_PEARL, 10));
        ItemStack incoming = new ItemStack(Items.ENDER_PEARL, 64);

        assertEquals(6, accessor(ctx).insert(incoming));
        assertEquals(16, ctx.getItem(0).getCount());
        assertEquals(58, incoming.getCount(), "余量留在来栈");
    }

    @Test
    @DisplayName("回滚超堆叠槽:100 + 12 ⇒ 恢复 112,不再被物品上限判满丢单")
    void rollback_superstackSlot_restoresBeyondItemMax() {
        FakeContainerContext ctx = new FakeContainerContext(1, 1).withSlotLimits(new int[]{512});
        ctx.set(0, new ItemStack(Items.REDSTONE, 100));

        accessor(ctx).rollback(new ItemStack(Items.REDSTONE, 12));

        assertEquals(112, ctx.getItem(0).getCount());
    }

    // ===== I-C12 写后回读（2026-11-05）=====

    @Test
    @DisplayName("插入写回静默失败:返回 0、来栈不扣、槽位保持空")
    void insert_writebackSilentlyFails_returnsZeroAndKeepsStack() {
        WriteIgnoringContext ctx = new WriteIgnoringContext(new int[]{64});
        ItemStack incoming = new ItemStack(Items.DIAMOND, 64);

        assertEquals(0, accessor(ctx).insert(incoming), "只认真正写进槽里的量");
        assertEquals(64, incoming.getCount(), "来栈只扣实际写入量(=0)");
        assertTrue(ctx.getItem(0).isEmpty(), "槽位没写进去");
    }

    @Test
    @DisplayName("抽取写回静默失败:返回 EMPTY、源槽原样(绝不把没离开源的货交给调用方)")
    void extract_writebackSilentlyFails_returnsEmptyAndKeepsSource() {
        WriteIgnoringContext ctx = new WriteIgnoringContext(new int[]{512});
        ctx.set(0, new ItemStack(Items.REDSTONE, 500));

        ItemStack out = accessor(ctx).extract(64, null);

        assertTrue(out.isEmpty(), "写回失败 ⇒ 整笔还原,交不出货");
        assertEquals(500, ctx.getItem(0).getCount(), "源槽原样");
    }
}
