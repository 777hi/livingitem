package com.qiqi.li.living.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.piston.PistonChainResolver;
import com.qiqi.li.living.domain.piston.PistonPlan;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.testutil.FakeContainerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

class MechanicalReceiversTest {

    private List<MechanicalReceivers.Receiver> saved;

    @BeforeEach
    void isolateRegistry() {
        saved = MechanicalReceivers.snapshotForTest();
        MechanicalReceivers.replaceForTest(List.of());
    }

    @AfterEach
    void restoreRegistry() {
        MechanicalReceivers.replaceForTest(saved);
    }

    private static TestContext context(ItemStack source, ItemStack receiver) {
        TestContext ctx = new TestContext(2);
        ctx.set(0, source);
        ctx.set(1, receiver);
        return ctx;
    }

    private static int dispatch(TestContext ctx) {
        return MechanicalReceivers.receive(ctx, ctx.getLevel(), new TickContext(ctx),
            0, 1, Pos2D.RIGHT);
    }

    private static class MatchingReceiver implements MechanicalReceivers.Receiver {
        private final int result;
        private final AtomicInteger calls;

        MatchingReceiver(int result, AtomicInteger calls) {
            this.result = result;
            this.calls = calls;
        }

        @Override
        public boolean matches(ItemStack receiverStack) {
            return receiverStack.is(Items.PISTON);
        }

        @Override
        public int receive(MechanicalReceivers.Request request) {
            calls.incrementAndGet();
            return result;
        }
    }

    private static final class OtherMatchingReceiver extends MatchingReceiver {
        OtherMatchingReceiver(int result, AtomicInteger calls) {
            super(result, calls);
        }
    }

    private static class TestContext extends FakeContainerContext {
        boolean exactWrite = true;
        boolean extractable = true;
        boolean corruptNextWrite;
        int slotLimit = 64;

        TestContext(int size) {
            super(size, size);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (corruptNextWrite) {
                corruptNextWrite = false;
                super.setItem(slot, stack.isEmpty() ? new ItemStack(Items.STONE) : stack.copyWithCount(1));
                return;
            }
            super.setItem(slot, stack);
        }

        @Override
        public boolean supportsExactWrite(int slot) {
            return exactWrite;
        }

        @Override
        public int simulateExtractItem(int slot, int amount) {
            return extractable ? super.simulateExtractItem(slot, amount) : 0;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slotLimit;
        }
    }

    @Test
    @DisplayName("注册表按实现类去重")
    void register_dedupesByClass() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        MechanicalReceivers.register(new MatchingReceiver(1, first));
        MechanicalReceivers.register(new MatchingReceiver(2, duplicate));

        assertEquals(1, dispatch(context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.PISTON))));
        assertEquals(1, first.get());
        assertEquals(0, duplicate.get());
    }

    @Test
    @DisplayName("角色探测只调用 matches,不执行接收")
    void matches_probesRegisteredReceiversWithoutDispatching() {
        AtomicInteger calls = new AtomicInteger();
        MechanicalReceivers.register(new MatchingReceiver(3, calls));

        assertTrue(MechanicalReceivers.matches(new ItemStack(Items.PISTON)));
        assertTrue(!MechanicalReceivers.matches(new ItemStack(Items.STONE)));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("活塞链解析通过中立端口发现非漏斗接收者")
    void pistonResolver_discoversRegisteredReceiverWithoutDomainKnowledge() {
        class BarrelReceiver implements MechanicalReceivers.Receiver {
            @Override public boolean matches(ItemStack stack) { return stack.is(Items.BARREL); }
            @Override public int receive(MechanicalReceivers.Request request) { return 0; }
        }
        MechanicalReceivers.register(new BarrelReceiver());
        TestContext ctx = new TestContext(3);
        ctx.set(1, new ItemStack(Items.DIRT));
        ctx.set(2, new ItemStack(Items.BARREL));

        PistonPlan.Receiver plan = org.junit.jupiter.api.Assertions.assertInstanceOf(
            PistonPlan.Receiver.class, PistonChainResolver.resolve(ctx, 0, Pos2D.RIGHT, 1));
        assertEquals(List.of(1), plan.cargoSlots());
        assertEquals(2, plan.receiverSlot());
    }

    @Test
    @DisplayName("无匹配接收端返回 0")
    void receive_noMatch_returnsZero() {
        AtomicInteger calls = new AtomicInteger();
        MechanicalReceivers.register(new MatchingReceiver(1, calls));

        assertEquals(0, dispatch(context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.STONE))));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("首个匹配者独占请求:返回 0 也不回落")
    void receive_firstMatchOwnsEvenWhenZero() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        MechanicalReceivers.register(new MatchingReceiver(0, first));
        MechanicalReceivers.register(new OtherMatchingReceiver(3, second));

        assertEquals(0, dispatch(context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.PISTON))));
        assertEquals(1, first.get());
        assertEquals(0, second.get());
    }

    @Test
    @DisplayName("请求原样携带上下文/槽号/方向,返回量限制在初始源数量")
    void receive_passesRequestAndClampsResult() {
        TestContext ctx = context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.PISTON));
        TickContext tick = new TickContext(ctx);
        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override
            public boolean matches(ItemStack receiverStack) {
                return true;
            }

            @Override
            public int receive(MechanicalReceivers.Request request) {
                assertSame(ctx, request.context());
                assertSame(tick, request.tick());
                assertEquals(0, request.sourceSlot());
                assertEquals(1, request.receiverSlot());
                assertEquals(Pos2D.LEFT, request.pushDirection());
                return 99;
            }
        });

        assertEquals(5, MechanicalReceivers.receive(ctx, ctx.getLevel(), tick, 0, 1, Pos2D.LEFT));
    }

    @Test
    @DisplayName("原始源访问器读取活跃存储物品本体,不展开内部内容")
    void bodySource_activeContainerIsNotExpanded() {
        ItemStack chest = new ItemStack(Items.CHEST, 3);
        LivingItemManager.setLiving(chest, true);
        chest.set(DataComponents.CONTAINER,
            ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 12))));
        TestContext ctx = context(chest, new ItemStack(Items.PISTON));

        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override
            public boolean matches(ItemStack receiverStack) {
                return true;
            }

            @Override
            public int receive(MechanicalReceivers.Request request) {
                ItemStack body = request.bodySource().simulateExtract(2);
                assertTrue(ItemStack.isSameItemSameComponents(body, chest));
                assertEquals(2, body.getCount());
                return 0;
            }
        });

        assertEquals(0, dispatch(ctx));
        assertEquals(3, ctx.getItem(0).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(ctx.getItem(0), chest));
    }

    @Test
    @DisplayName("原始源访问器读取活跃工具本体,不被活物品隔离规则屏蔽")
    void bodySource_activeToolIsNotFiltered() {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        LivingItemManager.setLiving(tool, true);
        TestContext ctx = context(tool, new ItemStack(Items.PISTON));

        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override
            public boolean matches(ItemStack receiverStack) {
                return true;
            }

            @Override
            public int receive(MechanicalReceivers.Request request) {
                ItemStack body = request.bodySource().simulateExtract(1);
                assertTrue(ItemStack.isSameItemSameComponents(body, tool));
                assertEquals(1, body.getCount());
                return 0;
            }
        });

        assertEquals(0, dispatch(ctx));
        assertTrue(ItemStack.isSameItemSameComponents(ctx.getItem(0), tool));
    }

    @Test
    @DisplayName("部分接收:transferAmount 返回 2,原始源由 7 扣到 5")
    void receive_partialTransfer_usesActualQuantity() {
        TestContext ctx = context(new ItemStack(Items.DIAMOND, 7), new ItemStack(Items.PISTON));
        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override
            public boolean matches(ItemStack receiverStack) {
                return true;
            }

            @Override
            public int receive(MechanicalReceivers.Request request) {
                return SlotAccessor.transferAmount(request.bodySource(), new LimitedTarget(2, 2), 7);
            }
        });

        assertEquals(2, dispatch(ctx));
        assertEquals(5, ctx.getItem(0).getCount());
        assertEquals(List.of(0), ctx.syncedSlots);
    }

    @Test
    @DisplayName("源槽不支持精确写入或禁止抽取:请求返回 0 且源不变")
    void receive_unsupportedExtraction_returnsZero() {
        AtomicInteger calls = new AtomicInteger();
        MechanicalReceivers.register(new MatchingReceiver(1, calls));
        TestContext unsupported = context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.PISTON));
        unsupported.exactWrite = false;

        assertEquals(0, dispatch(unsupported));
        assertEquals(5, unsupported.getItem(0).getCount());
        assertEquals(0, calls.get(), "精确写入不支持时不得进入接收端");

        TestContext noExtract = context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.PISTON));
        noExtract.extractable = false;
        MechanicalReceivers.replaceForTest(List.of(new MechanicalReceivers.Receiver() {
            @Override
            public boolean matches(ItemStack receiverStack) {
                return true;
            }

            @Override
            public int receive(MechanicalReceivers.Request request) {
                assertTrue(request.bodySource().simulateExtract(5).isEmpty());
                return SlotAccessor.transferAmount(request.bodySource(), new LimitedTarget(5, 5), 5);
            }
        }));

        assertEquals(0, dispatch(noExtract));
        assertEquals(5, noExtract.getItem(0).getCount());
    }

    @Test
    @DisplayName("本体源精确扣减写后不符:恢复原栈并报告 0")
    void bodySource_failedExactWrite_restoresOriginalStack() {
        TestContext ctx = context(new ItemStack(Items.DIAMOND, 7), new ItemStack(Items.PISTON));
        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override public boolean matches(ItemStack receiverStack) { return true; }
            @Override public int receive(MechanicalReceivers.Request request) {
                ctx.corruptNextWrite = true;
                return SlotAccessor.transferAmount(request.bodySource(), new LimitedTarget(2, 2), 2);
            }
        });

        assertEquals(0, dispatch(ctx));
        assertEquals(7, ctx.getItem(0).getCount());
        assertEquals(Items.DIAMOND, ctx.getItem(0).getItem());
    }

    @Test
    @DisplayName("本体槽精确插入写后不符:恢复原栈且来栈不扣减")
    void bodyTarget_failedExactWrite_restoresOriginalStack() {
        TestContext ctx = context(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.PISTON));
        BodySlotAccessor target = new BodySlotAccessor(ctx, 0);
        ItemStack input = new ItemStack(Items.DIAMOND, 2);
        ctx.corruptNextWrite = true;

        assertEquals(0, target.insert(input));
        assertEquals(5, ctx.getItem(0).getCount());
        assertEquals(Items.DIAMOND, ctx.getItem(0).getItem());
        assertEquals(2, input.getCount());
    }

    @Test
    @DisplayName("超大栈部分接收:余量可回到仍高于物品上限的原槽")
    void receive_oversizedBody_rollsBackBySlotLimit() {
        TestContext ctx = context(new ItemStack(Items.DIAMOND, 500), new ItemStack(Items.PISTON));
        ctx.slotLimit = 512;
        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override public boolean matches(ItemStack receiverStack) { return true; }
            @Override public int receive(MechanicalReceivers.Request request) {
                return SlotAccessor.transferAmount(request.bodySource(), new LimitedTarget(500, 2), 500);
            }
        });

        assertEquals(2, dispatch(ctx));
        assertEquals(498, ctx.getItem(0).getCount(), "余量按槽位上限回滚，不按物品 max=64 截断");
    }

    @Test
    @DisplayName("目标模拟谎报后仅收部分:余量回滚同一原始源槽")
    void receive_lyingTarget_rollsBackLeftover() {
        TestContext ctx = context(new ItemStack(Items.DIAMOND, 7), new ItemStack(Items.PISTON));
        MechanicalReceivers.register(new MechanicalReceivers.Receiver() {
            @Override
            public boolean matches(ItemStack receiverStack) {
                return true;
            }

            @Override
            public int receive(MechanicalReceivers.Request request) {
                return SlotAccessor.transferAmount(request.bodySource(), new LimitedTarget(7, 2), 7);
            }
        });

        assertEquals(2, dispatch(ctx));
        assertEquals(5, ctx.getItem(0).getCount(), "未插入的 5 个必须回原始源槽");
    }

    private static final class LimitedTarget implements SlotAccessor {
        private final int simulated;
        private final int actual;
        private int stored;

        LimitedTarget(int simulated, int actual) {
            this.simulated = simulated;
            this.actual = actual;
        }

        @Override
        public ItemStack extract(int amount, ItemStack filterType) {
            return ItemStack.EMPTY;
        }

        @Override
        public int insert(ItemStack stack) {
            int inserted = Math.min(actual, stack.getCount());
            stored += inserted;
            stack.shrink(inserted);
            return inserted;
        }

        @Override
        public ItemStack simulateExtract(int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public int simulateInsert(ItemStack stack) {
            return Math.min(simulated, stack.getCount());
        }

        @Override
        public void rollback(ItemStack stack) { }

        @Override
        public boolean isEmpty() {
            return stored == 0;
        }

        @Override
        public boolean isFull() {
            return false;
        }

        @Override
        public void markTransferred() { }

        @Override
        public void sync() { }
    }
}
