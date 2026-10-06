package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import com.qiqi.li.living.api.LivingItemFunction;

/**
 * 活物品 tooltip 段的<b>惰性标题</b>守卫（2026-10-07）。
 *
 * <p>先前是「无条件先输出空行 + {@code --- 活物品 ---} 标题，再逐功能追加行」，
 * 于是<b>零tooltip 行</b>的活物品（活桶：形态本身就是信息，定稿不写 tooltip）
 * 会留下一个空标题。现改为：先收集所有适用功能的行，<b>非空才</b>输出整段。</p>
 *
 * <p>这里只测「输出序列」纯逻辑（画面本身交游戏实测，客户端渲染无法单测）；
 * 被测方法把功能列表作为参数传入，因此测试可以喂 stub 而不必拉起全局注册表。</p>
 */
class LivingItemTooltipTest {

    /** stub 功能：{@code applies} 决定是否认领，{@code lines} 是它要输出的行。 */
    private static LivingItemFunction stub(java.util.function.Predicate<ItemStack> applies,
                                           List<String> lines) {
        return new LivingItemFunction() {
            @Override public boolean canApply(ItemStack stack) { return applies.test(stack); }
            @Override public String getFunctionId() { return "stub"; }
            @Override public void tick(List<SlotEntry> entries,
                    com.qiqi.li.living.container.ContainerContext container,
                    com.qiqi.li.living.container.TickContext tick,
                    net.minecraft.world.level.Level level) { /* no-op */ }
            @Override public void addToTooltip(Item.TooltipContext context,
                    Consumer<Component> tooltipAdder, TooltipFlag flag, ItemStack stack) {
                lines.forEach(s -> tooltipAdder.accept(Component.literal(s)));
            }
        };
    }

    private static List<Component> render(List<LivingItemFunction> functions, ItemStack stack) {
        List<Component> out = new ArrayList<>();
        LivingItemTooltip.renderSection(functions, Item.TooltipContext.EMPTY,
            TooltipFlag.NORMAL, stack, out::add);
        return out;
    }

    private static String keyOf(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents()).getKey();
    }

    private static ItemStack anyStack() {
        return new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET);
    }

    @Test
    @DisplayName("零功能行 ⇒ 整段不输出（不写空行、不写空标题）")
    void zeroLines_emitsNothing() {
        var out = render(List.of(), anyStack());
        assertTrue(out.isEmpty(), "没有可显示信息时不该留下「空行 + --- 活物品 ---」空标题");
    }

    @Test
    @DisplayName("零行但存在不适用该stack 的功能 ⇒ 同样整段不输出")
    void noApplicableFunction_emitsNothing() {
        var out = render(List.of(stub(s -> false, List.of("永远不输出"))), anyStack());
        assertTrue(out.isEmpty(), "canApply=false 的功能不产出行 ⇒ 视同零行");
    }

    @Test
    @DisplayName("有 1 行 ⇒ 空行 + 标题 + 该行（顺序固定）")
    void oneLine_emitsBlankTitleLine() {
        var out = render(List.of(stub(s -> true, List.of("行A"))), anyStack());

        assertEquals(3, out.size());
        assertEquals("", out.get(0).getString(), "第 0 行是分隔空行");
        assertEquals("tooltip.livingitem.title", keyOf(out.get(1)), "第 1 行是活物品标题");
        assertEquals("行A", out.get(2).getString());
    }

    @Test
    @DisplayName("多个功能 ⇒ 标题只出现一次、行序按注册顺序保持")
    void multipleFunctions_keepOrderUnderOneTitle() {
        var out = render(List.of(
                stub(s -> true, List.of("行A")),
                stub(s -> false, List.of("跳过")),
                stub(s -> true, List.of("行B", "行C"))), anyStack());

        assertEquals(5, out.size(), "空行 + 标题 + 3 行（跳过的那功能不产出行）");
        assertEquals("tooltip.livingitem.title", keyOf(out.get(1)));
        assertEquals(List.of("行A", "行B", "行C"),
            out.subList(2, 5).stream().map(Component::getString).toList());
    }
}