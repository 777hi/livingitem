package com.qiqi.li.living.domain.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * 活箱子 {@link LivingChestAccessor} 的接缝测试。
 *
 * <p>它长期是 0 引用（2026-10-29 测试域盘点）。这里钉住四条：
 * 模拟/真实同源（字节门）、回滚豁免（撤销不被禁止线挡住）、抽取快照门、同步/标记接线。</p>
 */
class LivingChestAccessorTest {

    private static final int SLOT = 3;

    private static ItemStack livingChest() {
        ItemStack chest = new ItemStack(Items.CHEST);
        LivingItemManager.setLiving(chest, true);
        return chest;
    }

    private static ItemStack bigBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT,
            new WritableBookContent(List.of(Filterable.passThrough("x".repeat(900)))));
        return book;
    }

    private static ItemStack nearByteFullChest() {
        ItemStack chest = livingChest();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(bigBook());
        }
        LivingChestFunction.setItems(chest, items);
        return chest;
    }

    private static ContainerSnapshot.ChestSnapshot snap(int used, boolean full, boolean byteFull) {
        return new ContainerSnapshot.ChestSnapshot(used, 0, full, byteFull);
    }

    private static LivingChestAccessor accessor(FakeContainerContext ctx, ItemStack chest,
                                                 ContainerSnapshot.ChestSnapshot snapshot) {
        return new LivingChestAccessor(ctx, SLOT, chest, LivingChestFunction.CHEST_SLOTS,
            new HashSet<>(), snapshot);
    }

    @Test
    @DisplayName("tryCreate:非活箱子返回 null,活箱子返回访问器")
    void tryCreate_onlyAcceptsLivingChest() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, new ItemStack(Items.CHEST));
        assertNull(LivingChestAccessor.tryCreate(null, ctx, SLOT, null, new HashSet<>(), ContainerSnapshot.EMPTY));

        ctx.set(SLOT, livingChest());
        LivingChestAccessor accessor = (LivingChestAccessor) LivingChestAccessor.tryCreate(
            null, ctx, SLOT, null, new HashSet<>(), ContainerSnapshot.EMPTY);
        assertNotNull(accessor);
        assertTrue(accessor.isEmpty(), "空快照 ⇒ isEmpty");
        assertFalse(accessor.isFull());
    }

    @Test
    @DisplayName("insert 未超限:全额收下,返回插入数,调用方栈被清空")
    void insert_underLimit_insertsAndMutatesInput() {
        ItemStack chest = livingChest();
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        LivingChestAccessor accessor = accessor(ctx, chest, snap(0, false, false));
        ItemStack input = new ItemStack(Items.DIAMOND, 2);

        int inserted = accessor.insert(input);

        assertEquals(2, inserted);
        assertTrue(input.isEmpty(), "SlotAccessor 契约：insert 修改传入栈为余量");
        assertEquals(2, LivingChestFunction.getItems(chest).get(0).getCount());
    }

    @Test
    @DisplayName("insert 字节超限:返回 0,来栈不动,内容不变")
    void insert_byteFull_returnsZero() {
        ItemStack chest = nearByteFullChest();
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        LivingChestAccessor accessor = accessor(ctx, chest, snap(20, false, true));
        ItemStack input = new ItemStack(Items.DIAMOND);

        int inserted = accessor.insert(input);

        assertEquals(0, inserted);
        assertEquals(1, input.getCount(), "拒绝 ⇒ 来栈不销毁");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest));
    }

    @Test
    @DisplayName("simulateInsert 与 insert 同源:字节超限时都返回 0")
    void simulateInsert_byteFull_returnsZero() {
        ItemStack chest = nearByteFullChest();
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        LivingChestAccessor accessor = accessor(ctx, chest, snap(20, false, true));
        ItemStack input = new ItemStack(Items.DIAMOND);

        assertEquals(0, accessor.simulateInsert(input), "模拟必须拒绝(旧实现用快照估算 ⇒ 说能收)");
        assertEquals(1, input.getCount(), "模拟不得修改来栈");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest), "模拟不得写内容");
    }

    @Test
    @DisplayName("simulateInsert 未超限:返回可接受数量,不写内容")
    void simulateInsert_underLimit_returnsCapacity() {
        ItemStack chest = livingChest();
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        LivingChestAccessor accessor = accessor(ctx, chest, snap(0, false, false));
        ItemStack input = new ItemStack(Items.DIAMOND, 2);

        assertEquals(2, accessor.simulateInsert(input));
        assertEquals(2, input.getCount(), "模拟不得修改来栈");
        assertTrue(LivingChestFunction.getItems(chest).get(0).isEmpty(), "模拟不得写内容");
    }

    @Test
    @DisplayName("rollback 字节超限:撤销仍收下(回滚豁免)")
    void rollback_byteFull_stillRestores() {
        ItemStack chest = nearByteFullChest();
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        LivingChestAccessor accessor = accessor(ctx, chest, snap(20, false, true));
        ItemStack extracted = new ItemStack(Items.DIAMOND);

        accessor.rollback(extracted);

        assertEquals(21, LivingChestFunction.countUsedSlots(chest), "回滚必须收下");
        assertTrue(LivingChestFunction.getItems(chest).stream().anyMatch(s -> s.is(Items.DIAMOND)),
            "回滚必须把钻石放回箱子");
    }

    @Test
    @DisplayName("extract:快照说空 ⇒ 不抽;有内容 ⇒ 抽首个非空并扣减")
    void extract_respectsSnapshotAndRemovesItem() {
        ItemStack chest = livingChest();
        LivingChestFunction.setItems(chest, List.of(bigBook(), new ItemStack(Items.DIAMOND)));
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);

        LivingChestAccessor emptySnap = accessor(ctx, chest, snap(0, false, false));
        assertTrue(emptySnap.extract(1, null).isEmpty(), "快照说空 ⇒ 守卫直接返回");

        LivingChestAccessor accessor = accessor(ctx, chest, snap(2, false, false));
        ItemStack simulated = accessor.simulateExtract(1);
        assertEquals(Items.WRITABLE_BOOK, simulated.getItem(), "模拟抽首个非空");
        assertEquals(2, LivingChestFunction.countUsedSlots(chest), "模拟不写内容");

        ItemStack extracted = accessor.extract(1, null);
        assertEquals(Items.WRITABLE_BOOK, extracted.getItem());
        assertEquals(1, LivingChestFunction.countUsedSlots(chest), "真实抽取后只剩钻石");
    }

    @Test
    @DisplayName("回滚满箱子:只报告,不写其它槽/不落地")
    void rollback_fullChest_reportsAndDoesNotWriteElsewhere() {
        ItemStack chest = livingChest();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            items.add(new ItemStack(Items.STONE, 64));
        }
        LivingChestFunction.setItems(chest, items);
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        LivingChestAccessor accessor = accessor(ctx, chest, snap(27, true, false));

        accessor.rollback(new ItemStack(Items.DIAMOND));

        assertEquals(27, LivingChestFunction.countUsedSlots(chest));
        assertTrue(LivingChestFunction.getItems(chest).stream().noneMatch(s -> s.is(Items.DIAMOND)),
            "满箱子回滚失败 ⇒ 只记日志，不得塞到别处");
    }

    @Test
    @DisplayName("markTransferred / sync:接线到传输集合与容器同步")
    void markAndSync_reachTheContainer() {
        ItemStack chest = livingChest();
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(SLOT, chest);
        Set<Integer> transferred = new HashSet<>();
        LivingChestAccessor accessor = new LivingChestAccessor(ctx, SLOT, chest,
            LivingChestFunction.CHEST_SLOTS, transferred, snap(0, false, false));

        accessor.markTransferred();
        accessor.sync();

        assertTrue(transferred.contains(SLOT), "落点标记必须写入");
        assertTrue(ctx.syncedSlots.contains(SLOT), "sync 必须通知容器");
    }
}
