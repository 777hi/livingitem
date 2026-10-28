package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link SlotAccessor#transfer} 的守恒不变量测试。
 *
 * <p>传输引擎允许“模拟说能收、真实只收一部分”，此时必须把余量回滚给源。
 * 这里用单槽替身覆盖四条分支：诚实全额 / 诚实部分 / 谎报部分 / 谎报全拒，
 * 再跑一轮固定种子的随机组合，断言**源+目标总量守恒**（不复制、不蒸发）。</p>
 *
 * <p>替身严格遵守 {@link SlotAccessor} 契约：{@code insert} 把传入栈改成余量。</p>
 */
class SlotAccessorTransferConservationTest {

    /** 单槽替身；simulateAccept / realAccept 为负数表示“诚实”。 */
    private static final class FakeAccessor implements SlotAccessor {
        ItemStack slot;
        int simulateAccept = -1;
        int realAccept = -1;

        FakeAccessor(ItemStack slot) {
            this.slot = slot.copy();
        }

        private int space() {
            return Math.max(0, 64 - slot.getCount());
        }

        @Override
        public ItemStack simulateExtract(int amount) {
            if (slot.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            return slot.copyWithCount(Math.min(amount, slot.getCount()));
        }

        @Override
        public ItemStack extract(int amount, ItemStack filterType) {
            if (slot.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int n = Math.min(amount, slot.getCount());
            ItemStack out = slot.copyWithCount(n);
            slot.shrink(n);
            if (slot.isEmpty()) slot = ItemStack.EMPTY;
            return out;
        }

        @Override
        public int simulateInsert(ItemStack stack) {
            if (stack.isEmpty()) return 0;
            int honest = Math.min(space(), stack.getCount());
            return simulateAccept < 0 ? honest : Math.min(simulateAccept, stack.getCount());
        }

        @Override
        public int insert(ItemStack stack) {
            if (stack.isEmpty()) return 0;
            int actual = Math.min(Math.min(space(), stack.getCount()),
                realAccept < 0 ? Integer.MAX_VALUE : realAccept);
            if (actual <= 0) return 0;
            if (slot.isEmpty()) {
                slot = stack.copyWithCount(actual);
            } else {
                slot.grow(actual);
            }
            stack.shrink(actual);
            if (stack.isEmpty()) stack.setCount(0);
            return actual;
        }

        @Override
        public void rollback(ItemStack stack) {
            if (stack.isEmpty()) return;
            if (slot.isEmpty()) {
                slot = stack.copy();
            } else {
                slot.grow(stack.getCount());
            }
            stack.setCount(0);
        }

        @Override
        public boolean isEmpty() {
            return slot.isEmpty();
        }

        @Override
        public boolean isFull() {
            return !slot.isEmpty() && slot.getCount() >= 64;
        }

        @Override
        public void markTransferred() {}

        @Override
        public void sync() {}
    }

    private static FakeAccessor source(int count) {
        return new FakeAccessor(new ItemStack(Items.DIAMOND, count));
    }

    private static FakeAccessor target(int prefill) {
        return new FakeAccessor(new ItemStack(Items.DIAMOND, prefill));
    }

    private static int total(FakeAccessor a, FakeAccessor b) {
        return a.slot.getCount() + b.slot.getCount();
    }

    @Test
    @DisplayName("诚实全额:源扣减,目标增加,总量守恒")
    void honestTransfer_conserves() {
        FakeAccessor source = source(10);
        FakeAccessor target = target(0);

        assertTrue(SlotAccessor.transfer(source, target, 10));

        assertEquals(0, source.slot.getCount());
        assertEquals(10, target.slot.getCount());
        assertEquals(10, total(source, target));
    }

    @Test
    @DisplayName("诚实部分:目标只剩 6 格 ⇒ 只搬 6,余量留在源")
    void honestPartial_conserves() {
        FakeAccessor source = source(10);
        FakeAccessor target = target(58);
        int before = total(source, target);

        assertTrue(SlotAccessor.transfer(source, target, 10));

        assertEquals(64, target.slot.getCount());
        assertEquals(4, source.slot.getCount(), "目标装不下的 4 个必须留在源");
        assertEquals(before, total(source, target));
    }

    @Test
    @DisplayName("谎报部分:模拟说能收 10,真实只收 6 ⇒ 余 4 回滚,守恒")
    void lyingSimulate_partialInsert_rollsBackRemainder() {
        FakeAccessor source = source(10);
        FakeAccessor target = target(58);
        target.simulateAccept = 10;
        target.realAccept = 6;
        int before = total(source, target);

        assertTrue(SlotAccessor.transfer(source, target, 10));

        assertEquals(64, target.slot.getCount());
        assertEquals(4, source.slot.getCount(), "部分插入的余量必须回滚");
        assertEquals(before, total(source, target));
    }

    @Test
    @DisplayName("谎报全拒:模拟说能收,真实收 0 ⇒ 全量回滚,守恒")
    void lyingSimulate_rejectInsert_rollsBackAll() {
        FakeAccessor source = source(10);
        FakeAccessor target = target(0);
        target.simulateAccept = 10;
        target.realAccept = 0;
        int before = total(source, target);

        assertFalse(SlotAccessor.transfer(source, target, 10));

        assertEquals(10, source.slot.getCount());
        assertEquals(0, target.slot.getCount());
        assertEquals(before, total(source, target));
    }

    @Test
    @DisplayName("空源 / 满目标:不动作,总量不变")
    void emptyOrFull_noOp() {
        FakeAccessor emptySource = source(0);
        FakeAccessor target = target(0);
        assertFalse(SlotAccessor.transfer(emptySource, target, 10));
        assertEquals(0, total(emptySource, target));

        FakeAccessor source = source(10);
        FakeAccessor fullTarget = target(64);
        int before = total(source, fullTarget);
        assertFalse(SlotAccessor.transfer(source, fullTarget, 10));
        assertEquals(before, total(source, fullTarget));
        assertEquals(10, source.slot.getCount());
    }

    @Test
    @DisplayName("提取量受目标模拟上限约束:目标只能收 3 ⇒ 只抽 3")
    void extractLimitedBySimulatedCapacity() {
        FakeAccessor source = source(10);
        FakeAccessor target = target(61); // 3 格空间

        assertTrue(SlotAccessor.transfer(source, target, 10));

        assertEquals(7, source.slot.getCount(), "只应抽 3");
        assertEquals(64, target.slot.getCount());
        assertEquals(71, total(source, target));
    }

    @Test
    @DisplayName("随机组合:源数量 × 目标预填 × 模拟/真实接受量 ⇒ 总量始终守恒")
    void randomized_conservation() {
        Random random = new Random(20261029L);
        for (int i = 0; i < 300; i++) {
            int sourceCount = random.nextInt(33);
            int targetPrefill = random.nextInt(65);
            int amount = random.nextInt(40);
            FakeAccessor source = source(sourceCount);
            FakeAccessor target = target(targetPrefill);
            target.simulateAccept = random.nextInt(70);
            target.realAccept = random.nextInt(70);
            int before = sourceCount + targetPrefill;

            SlotAccessor.transfer(source, target, amount);

            String context = "iteration=" + i + " source=" + sourceCount + " target=" + targetPrefill
                + " amount=" + amount + " sim=" + target.simulateAccept
                + " real=" + target.realAccept;
            assertEquals(before, total(source, target), context);
            assertTrue(source.slot.getCount() >= 0 && target.slot.getCount() >= 0, "不得出现负数: " + context);
            assertTrue(source.slot.getCount() <= 64 && target.slot.getCount() <= 64,
                "不得超过堆叠上限: " + context);
        }
    }
}
