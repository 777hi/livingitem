package com.qiqi.li.living.domain.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerDataKeys;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 红石**驱动链路**的端到端测试 —— 走真实 {@code ContainerLivingItemHandler.processContext}。
 *
 * <p><b>为什么需要</b>：{@link ContainerRedstoneDataTest} 覆盖的是**引擎内部**
 * （直接调 {@code calculate()}，20+ 例：传播 / 衰减 / 中继器 / 比较器 / 火把振荡…），
 * 但它**绕过了驱动链路** —— 而 2026-10-08 的「重算收归单点」重构改的恰恰是这一层：
 * 谁调 {@code calculate}（10 处 → 1 处）、什么时候<b>不</b>调（守卫）、自维持、prio 位置。</p>
 *
 * <p>本类把 {@code docs/buffer/redstone-driver-consolidation-plan.md} §7「代价与风险」
 * 表里的论断变成**可执行断言** —— 尤其「无关容器零开销」与「守卫不漏消费者」两条，
 * 它们此前只有论证、没有验证。</p>
 *
 * <p>容器约定：9 格 handler ⇒ 宽度推断 9 ⇒ 1×9 单行。
 * 消费者统一用<b>活 TNT</b>（它的 {@code tick()} 只读信号、不搬物品，
 * 在 mock 世界下无副作用；漏斗的 tick 会尝试真实传输，不适合本层测试）。</p>
 */
class ContainerRedstoneIntegrationTest {

    /** 模拟真实 IItemHandler 语义（同 ContainerFluidIntegrationTest）。 */
    private static class FakeHandler implements IItemHandlerModifiable {
        final ItemStack[] slots;

        FakeHandler(ItemStack... slots) { this.slots = slots; }

        @Override public int getSlots() { return slots.length; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slots[slot] != null ? slots[slot] : ItemStack.EMPTY;
        }
        @Override public void setStackInSlot(int slot, ItemStack stack) { slots[slot] = stack; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    }

    /** 构造一个活物品（已打上 IS_LIVING 标记）。 */
    private static ItemStack living(Item item) {
        ItemStack s = new ItemStack(item);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    /** 服务端 Level mock（带递增世界时钟）。 */
    private static Level mockServerLevel() {
        Level level = org.mockito.Mockito.mock(Level.class);
        org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
        long[] tick = {0};
        org.mockito.Mockito.when(level.getGameTime()).thenAnswer(inv -> tick[0]++);
        return level;
    }

    // ════════════════════════════════════════
    // 1. 自维持（「只有活按钮」的容器也能被驱动的基础）
    // ════════════════════════════════════════

    @Test
    @DisplayName("驱动链路：LivingRedstoneFunction 已注册进自维持清单")
    void redstoneDriver_isRegisteredSelfSustaining() {
        boolean found = LivingItemManager.getSelfSustainingFunctions().stream()
            .anyMatch(f -> f instanceof LivingRedstoneFunction);
        assertTrue(found,
            "红石驱动必须进自维持清单 —— 否则容器里只有活按钮/拉杆（没有活红石粉）时无人驱动红石层");
    }

    // ════════════════════════════════════════
    // 2. 守卫放行：消费者在场
    // ════════════════════════════════════════

    @Test
    @DisplayName("驱动链路：容器里只有活 TNT（消费者）⇒ 账本被创建且 calculate 跑过")
    void consumerPresence_letsGuardPass() {
        ItemStack[] slots = new ItemStack[9];
        slots[0] = living(Items.TNT);
        Level level = mockServerLevel();
        var ctx = new SimpleContainerContext(new FakeHandler(slots), level);

        assertNull(ctx.peekContainerData(ContainerDataKeys.REDSTONE), "前置：尚无红石账本");

        ContainerLivingItemHandler.processContext(ctx, level);

        var rd = ctx.peekContainerData(ContainerDataKeys.REDSTONE);
        assertNotNull(rd,
            "TNT 在 tick() 里调 getOrCreateRedstoneData ⇒ 账本必然被创建（守卫判据之一）");
        assertTrue(rd.hasEdgeHistory(),
            "且必须真的跑过 calculate —— 守卫不能把消费者拦下（「守卫不漏消费者」的回归守卫）");
    }

    // ════════════════════════════════════════
    // 3. 守卫拦截：与红石无关的容器（零开销）
    // ════════════════════════════════════════

    @Test
    @DisplayName("驱动链路：纯活熔炉容器 ⇒ 不创建红石账本（零开销）")
    void unrelatedContainer_neverCreatesRedstoneData() {
        ItemStack[] slots = new ItemStack[9];
        slots[0] = living(Items.FURNACE);
        Level level = mockServerLevel();
        var ctx = new SimpleContainerContext(new FakeHandler(slots), level);

        ContainerLivingItemHandler.processContext(ctx, level);

        assertNull(ctx.peekContainerData(ContainerDataKeys.REDSTONE),
            "与红石无关的容器不该创建账本 —— 守卫必须拦住。"
            + "（自维持 + 无守卫 = 每个被 tick 的容器每 tick 白跑一次 calculate，"
            + "内含 notifyBoundaryChange → level.updateNeighborsAt）");
    }

    // ════════════════════════════════════════
    // 4. 残留归零（原 zeroResidualRedstone 的职责）
    // ════════════════════════════════════════

    @Test
    @DisplayName("驱动链路：残留红石账本在空容器里仍被归零（原 zeroResidualRedstone 职责已由守卫接管）")
    void residualLedger_stillZeroedByGuard() {
        ItemStack[] slots = new ItemStack[9];
        Level level = mockServerLevel();
        var ctx = new SimpleContainerContext(new FakeHandler(slots), level);

        var rd = ctx.getOrCreateContainerData(ContainerDataKeys.REDSTONE);
        assertFalse(rd.hasEdgeHistory(), "前置：本会话尚未 calculate 过");

        ContainerLivingItemHandler.processContext(ctx, level);

        assertTrue(rd.hasEdgeHistory(),
            "有残留账本 ⇒ 守卫放行 ⇒ calculate 照跑（hasAny=false 分支归零）。"
            + "2026-10-08 前这项工作由 ContainerLivingItemHandler.zeroResidualRedstone 兜底，该守卫已删。");
    }

    // ════════════════════════════════════════
    // 5. prio 位置（电力依赖红石已算完）
    // ════════════════════════════════════════

    @Test
    @DisplayName("驱动链路：红石 prio 2 < 电力 prio 3（电力依赖本 tick 已算完的 edgeGrid）")
    void priorities_redstoneStrictlyBeforePower() {
        int redstone = new LivingRedstoneFunction().getPriority();
        int power = new LivingWaxedCopperFunction().getPriority();

        assertEquals(2, redstone, "红石层必须保持在 prio 2");
        assertTrue(power > redstone,
            "电力 prio 必须严格大于红石 —— 否则电力采样读到上一 tick 的 edgeGrid"
            + "（power-invariants.md / 红电系统.md 的硬约束）");
    }
}
