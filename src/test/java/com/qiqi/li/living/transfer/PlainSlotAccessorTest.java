package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link PlainSlotAccessor} 的回滚契约：**只回原槽；装不下/被占就报告，不找其它槽、不落地**。
 */
class PlainSlotAccessorTest {

    private static PlainSlotAccessor accessor(FakeContainerContext ctx) {
        return new PlainSlotAccessor(ctx, 0, new HashSet<>());
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
}
