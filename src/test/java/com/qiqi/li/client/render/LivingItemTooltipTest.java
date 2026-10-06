package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.water.LivingBucketFunction;

/**
 * 活物品 tooltip 段的<b>标题恒在</b>守卫（2026-10-07）。
 *
 * <p>定稿（用户拍板「<b>标题不要删，标题是正常的</b>」）：「--- 活物品 ---」是
 * <b>「这个物品是活物品」标记</b>，不是内容行 ——哪怕功能一行都不产出
 * （活桶：形态本身就是信息，定稿不写 tooltip），标题与其上方分隔空行<b>照旧输出</b>。
 * 曾试过「零行则整段不输出」的惰性标题，已否（会把标记一起丢掉）。</p>
 *
 * <p>这里只测「输出序列」纯逻辑（画面本身交游戏实测，客户端渲染无法单测）；
 * 被测方法把功能列表作为参数传入，因此测试可以喂 stub 而不必拉起全局注册表。</p>
 */
class LivingItemTooltipTest {

    /** stub 功能：{@code applies} 决定是否认领，{@code lines} 是它要输出的行。 */
    private static LivingItemFunction stub(Predicate<ItemStack> applies, List<String> lines) {
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
        return new ItemStack(Items.WATER_BUCKET);
    }

    @Test
    @DisplayName("零功能行 ⇒ **仍输出**分隔空行 + 标题（标记不能丢）")
    void zeroLines_keepsTitle() {
        var out = render(List.of(), anyStack());

        assertEquals(2, out.size(), "只有「空行 + 标题」，但不能什么都不输出");
        assertEquals("", out.get(0).getString());
        assertEquals("tooltip.livingitem.title", keyOf(out.get(1)));
    }

    @Test
    @DisplayName("零行但存在不适用该 stack 的功能 ⇒ 同样保留标题")
    void noApplicableFunction_keepsTitle() {
        var out = render(List.of(stub(s -> false, List.of("永远不输出"))), anyStack());

        assertEquals(2, out.size());
        assertEquals("tooltip.livingitem.title", keyOf(out.get(1)));
    }

    @Test
    @DisplayName("真实活桶 ⇒ 只有标题、没有内容行（两条定稿合起来的表现）")
    void livingBucket_titleOnly() {
        var bucket = new ItemStack(Items.WATER_BUCKET);
        LivingItemManager.setLiving(bucket, true);

        var out = render(List.of(new LivingBucketFunction()), bucket);

        assertEquals(2, out.size(), "活桶定稿不写内容行（形态本身就是信息）");
        assertEquals("tooltip.livingitem.title", keyOf(out.get(1)));
    }

    @Test
    @DisplayName("有内容行 ⇒ 空行 + 标题 + 各行（顺序固定）")
    void oneLine_appendsAfterTitle() {
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