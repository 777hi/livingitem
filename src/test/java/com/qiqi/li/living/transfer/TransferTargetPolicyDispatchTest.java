package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link TransferTargetPolicies} 分发语义（纯桩策略，不涉及具体领域）：
 *
 * <ul>
 *   <li>目标无策略接管 → 源侧探测触发 + CONTINUE（回落通用传输）</li>
 *   <li>目标被策略 REJECT → 探测不触发（旧 resolveTarget 时序：被拒不改源状态）</li>
 *   <li>目标被策略接管（HANDLED）→ 源侧探测不触发（由策略自理）</li>
 *   <li>跨容器拉取：null = 无接管（调用方回落），非 null = 接管结果</li>
 * </ul>
 */
class TransferTargetPolicyDispatchTest {

    /** 只认指定物品的策略桩：记录调用次数，intercept 返回预置决策。 */
    private static final class StubPolicy implements TransferTargetPolicy {
        final Item matchItem;
        final Outcome outcome;
        int interceptCalls;
        int probeCalls;
        Integer probedTargetSlot = null;

        StubPolicy(Item matchItem, Outcome outcome) {
            this.matchItem = matchItem;
            this.outcome = outcome;
        }

        @Override
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && stack.is(matchItem);
        }

        @Override
        public Outcome interceptInContainer(SlotAccessor source, SlotAccessor target,
                                            ContainerContext ctx, net.minecraft.world.level.Level level,
                                            int sourceSlot, int targetSlot, int hostSlot,
                                            int stackSize, int maxTransfer) {
            interceptCalls++;
            return outcome;
        }

        @Override
        public boolean interceptCrossContainerPull(ContainerContext containerCtx,
                                                   net.minecraft.world.level.Level level,
                                                   net.neoforged.neoforge.items.IItemHandler neighborHandler,
                                                   net.minecraft.core.BlockPos neighborPos,
                                                   ItemStack targetStack, int targetSlot,
                                                   int stackSize, int maxTransfer,
                                                   FilterData filterData, int hostSlot) {
            interceptCalls++;
            return true;
        }

        @Override
        public void onProbingTarget(SlotAccessor source, ContainerContext ctx,
                                    int targetSlot, ItemStack targetStack) {
            probeCalls++;
            probedTargetSlot = targetSlot;
        }
    }

    private List<TransferTargetPolicy> realPolicies;

    @BeforeEach
    void snapshotRealPolicies() {
        realPolicies = TransferTargetPolicies.snapshotForTest();
    }

    @AfterEach
    void restoreRealPolicies() {
        TransferTargetPolicies.replaceForTest(realPolicies);
    }

    @Test
    @DisplayName("目标无策略接管：源侧探测触发，返回 CONTINUE 回落通用传输")
    void noTargetPolicy_probesSource_andContinues() {
        StubPolicy sourcePolicy = new StubPolicy(Items.APPLE, TransferTargetPolicy.Outcome.HANDLED);
        TransferTargetPolicies.replaceForTest(List.of(sourcePolicy));

        TransferTargetPolicy.Outcome outcome = TransferTargetPolicies.interceptInContainer(
            null, new ItemStack(Items.APPLE), null, new ItemStack(Items.DIAMOND),
            null, null, 0, 1, 2, 64, 64);

        assertEquals(TransferTargetPolicy.Outcome.CONTINUE, outcome);
        assertEquals(0, sourcePolicy.interceptCalls, "目标未被接管 → intercept 不应被调");
        assertEquals(1, sourcePolicy.probeCalls, "源侧探测应恰好触发一次");
        assertEquals(1, sourcePolicy.probedTargetSlot, "探测应拿到目标槽位");
    }

    @Test
    @DisplayName("目标被策略 REJECT：探测不触发（被拒不改源状态，对齐旧 resolveTarget 时序）")
    void targetRejected_noProbe() {
        StubPolicy targetPolicy = new StubPolicy(Items.DIAMOND, TransferTargetPolicy.Outcome.REJECT);
        StubPolicy sourcePolicy = new StubPolicy(Items.APPLE, TransferTargetPolicy.Outcome.HANDLED);
        TransferTargetPolicies.replaceForTest(List.of(sourcePolicy, targetPolicy));

        TransferTargetPolicy.Outcome outcome = TransferTargetPolicies.interceptInContainer(
            null, new ItemStack(Items.APPLE), null, new ItemStack(Items.DIAMOND),
            null, null, 0, 1, 2, 64, 64);

        assertEquals(TransferTargetPolicy.Outcome.REJECT, outcome);
        assertEquals(1, targetPolicy.interceptCalls);
        assertEquals(0, sourcePolicy.probeCalls, "REJECT 路径不得触碰源状态");
    }

    @Test
    @DisplayName("目标被策略接管（HANDLED）：源侧探测不触发，由策略自理")
    void targetHandled_noProbe() {
        StubPolicy targetPolicy = new StubPolicy(Items.DIAMOND, TransferTargetPolicy.Outcome.HANDLED);
        StubPolicy sourcePolicy = new StubPolicy(Items.APPLE, TransferTargetPolicy.Outcome.HANDLED);
        TransferTargetPolicies.replaceForTest(List.of(sourcePolicy, targetPolicy));

        TransferTargetPolicy.Outcome outcome = TransferTargetPolicies.interceptInContainer(
            null, new ItemStack(Items.APPLE), null, new ItemStack(Items.DIAMOND),
            null, null, 0, 1, 2, 64, 64);

        assertEquals(TransferTargetPolicy.Outcome.HANDLED, outcome);
        assertEquals(0, sourcePolicy.probeCalls, "接管路径的源状态由策略自己处理");
    }

    @Test
    @DisplayName("跨容器拉取：无策略接管返回 null（调用方回落）；有接管返回其结果")
    void crossContainerPull_nullMeansUntouched() {
        StubPolicy applePolicy = new StubPolicy(Items.APPLE, TransferTargetPolicy.Outcome.HANDLED);
        TransferTargetPolicies.replaceForTest(List.of(applePolicy));

        assertNull(TransferTargetPolicies.interceptCrossContainerPull(
                null, null, null, null, new ItemStack(Items.DIAMOND), 3, 64, 64, null, 0),
            "目标无策略 → null，调用方走通用拉取");

        Boolean handled = TransferTargetPolicies.interceptCrossContainerPull(
            null, null, null, null, new ItemStack(Items.APPLE), 3, 64, 64, null, 0);
        assertTrue(handled, "目标被接管 → 返回接管结果（不回落）");
        assertFalse(handled == null);
    }
}
