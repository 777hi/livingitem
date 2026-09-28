package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

/**
 * tick 顺序契约守卫（A1 配套，api-contract.md §2.4）。
 *
 * <p>⭐ <b>为什么需要</b>：A1 把注册按域下放成 11 个分散文件后，
 * tick 顺序变成「主类调用次序 × 各文件内部排列」—— 没有任何一处能读出完整顺序，
 * 也看不出哪些相邻是有意的、哪些只是巧合。
 * 本测试锁住的是：顺序从此由 {@code getTickPriority()} <b>声明</b>出来。</p>
 *
 * <p>⚠️ <b>最关键的一条是 {@link #allFunctionsDefaultToPriorityZero}</b>：
 * 它证明「默认优先级全为 0 + 排序稳定 ⇒ 顺序与下放前完全一致」，
 * 即这次改造是<b>零行为变化</b>的。将来任何人给某个功能设了非零优先级，
 * 这条会失败 —— 那正是提醒他「你正在改变 tick 顺序，请确认这是有意的」。</p>
 */
class TickOrderTest {

    /** 所有功能默认优先级为 0 ⇒ 排序不改变顺序（零行为变化的证据）。 */
    @Test
    void allFunctionsDefaultToPriorityZero() {
        List<LivingItemFunction> all = LivingItemManager.getAllFunctions();
        assertTrue(!all.isEmpty(), "测试环境应已注册功能");
        for (LivingItemFunction f : all) {
            assertEquals(0, f.getTickPriority(),
                f.getFunctionId() + " 未声明优先级时应为 0 —— 非 0 意味着 tick 顺序被有意改动");
        }
    }

    /** 排序必须稳定：优先级相同时保持注册顺序。 */
    @Test
    void sortIsStableForEqualPriority() {
        List<LivingItemFunction> input =
            List.of(new Fake("a", 0), new Fake("b", 0), new Fake("c", 0));
        assertEquals(List.of("a", "b", "c"), idsOf(LivingItemManager.sortByPriority(input)));
    }

    /** 优先级小的先执行；同优先级内保持注册顺序。 */
    @Test
    void sortOrdersByPriorityThenKeepsRegistrationOrder() {
        List<LivingItemFunction> input =
            List.of(new Fake("a", 5), new Fake("b", 1), new Fake("c", 5), new Fake("d", 1));
        // 优先级 1：b、d（保持原有相对顺序）；优先级 5：a、c（同样保持）
        assertEquals(List.of("b", "d", "a", "c"), idsOf(LivingItemManager.sortByPriority(input)));
    }

    /** 对外列表只读（外部无法篡改 tick 顺序），且按优先级非降序。 */
    @Test
    void listIsReadOnlyAndOrdered() {
        List<LivingItemFunction> all = LivingItemManager.getAllFunctions();
        assertThrows(UnsupportedOperationException.class, () -> all.add(null),
            "getAllFunctions() 必须返回只读视图 —— 外部不应能篡改 tick 顺序");
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).getTickPriority() <= all.get(i).getTickPriority(),
                "tick 顺序必须按 getTickPriority 非降序");
        }
    }

    private static List<String> idsOf(List<LivingItemFunction> list) {
        return list.stream().map(LivingItemFunction::getFunctionId).toList();
    }

    /** 最小实现：只关心 id 与优先级，其余不参与被测逻辑。 */
    private record Fake(String id, int priority) implements LivingItemFunction {
        @Override public boolean canApply(ItemStack stack) { return false; }
        @Override public String getFunctionId() { return id; }
        @Override public int getTickPriority() { return priority; }

        @Override
        public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) { }

        @Override
        public void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                java.util.function.Consumer<Component> tooltipAdder,
                net.minecraft.world.item.TooltipFlag flag, ItemStack stack) { }
    }
}
