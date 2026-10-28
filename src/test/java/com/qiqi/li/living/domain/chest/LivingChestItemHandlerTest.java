package com.qiqi.li.living.domain.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * 活箱子<b>对外能力面</b>（{@link LivingChestItemHandler}）的接缝测试。
 *
 * <p>这个面是漏斗 / 管道 / 第三方模组唯一能碰到的入口，长期没有直接测试
 * （2026-10-29 测试域盘点：0 引用）。首轮接缝测试抓到两个真实缺陷：</p>
 * <ol>
 *   <li><b>复制</b>：真实插入把 {@code stack.copy()} 交给整库插入，却用原栈的 count
 *       计算余量 ⇒ 箱子收到一份、调用方也拿回一份；</li>
 *   <li><b>字节门失效</b>：能力面只拿到 {@code ItemStack}，旧实现走 64B/槽的估算路径
 *       ⇒ 27 槽永远碰不到 16KB（{@code living-chest-tech.md} §2.3）。</li>
 * </ol>
 */
class LivingChestItemHandlerTest {

    /** NeoForge 已给 {@code EMPTY} 打补丁：查不到时回落 BuiltInRegistries ⇒ 可算精确 NBT。 */
    private static final Provider REGISTRIES = RegistryAccess.EMPTY;

    private static ItemStack bigBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT,
            new WritableBookContent(List.of(Filterable.passThrough("x".repeat(900)))));
        return book;
    }

    /** 20 本成书 + 7 空槽 ⇒ 精确体积超上限，但有空槽（隔离“字节满”与“槽位满”）。 */
    private static ItemStack chestNearByteFull() {
        ItemStack chest = new ItemStack(Items.CHEST);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(bigBook());
        }
        LivingChestFunction.setItems(chest, items);
        return chest;
    }

    @Test
    @DisplayName("能力面全额插入:箱子收到 1 个,余量为空,调用方栈不被修改")
    void insert_acceptsAndReturnsEmpty() {
        ItemStack chest = new ItemStack(Items.CHEST);
        LivingChestItemHandler handler = new LivingChestItemHandler(chest);
        ItemStack input = new ItemStack(Items.DIAMOND);

        ItemStack remainder = handler.insertItem(0, input, false);

        assertTrue(remainder.isEmpty(), "全额收下 ⇒ 余量为空（旧实现返回原栈 ⇒ 复制）");
        assertEquals(1, LivingChestFunction.getItems(chest).get(0).getCount(), "箱子必须只收到 1 个");
        assertEquals(1, input.getCount(), "IItemHandler 约定：不得修改调用方传入的栈");
    }

    @Test
    @DisplayName("能力面部分插入:只装下 1 个 ⇒ 余量 1,箱子精确增加 1")
    void insert_partial_returnsRemainder() {
        ItemStack chest = new ItemStack(Items.CHEST);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            items.add(new ItemStack(Items.STONE, 64));
        }
        items.add(new ItemStack(Items.DIAMOND, 63));
        LivingChestFunction.setItems(chest, items);
        LivingChestItemHandler handler = new LivingChestItemHandler(chest);

        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.DIAMOND, 2), false);

        assertEquals(1, remainder.getCount(), "只装下 1 个 ⇒ 余量 1（旧实现余量 2 ⇒ 复制 1 个）");
        assertEquals(64, LivingChestFunction.getItems(chest).get(26).getCount(), "箱子只增加 1 个");
    }

    @Test
    @DisplayName("能力面字节门:精确超限(但有空槽) ⇒ 真实插入拒绝,内容不变")
    void insert_rejectsWhenByteFull() {
        ItemStack chest = chestNearByteFull();
        assertTrue(LivingChestFunction.getCurrentByteUsage(chest, REGISTRIES)
            >= LivingChestFunction.MAX_STORAGE_BYTES, "前置:必须精确超限");

        LivingChestItemHandler handler = new LivingChestItemHandler(chest);
        ItemStack input = new ItemStack(Items.DIAMOND);
        ItemStack remainder = handler.insertItem(0, input, false);

        assertEquals(1, remainder.getCount(), "超限 ⇒ 原样退回");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest), "拒绝 ⇒ 内容不变");
        assertFalse(handler.isItemValid(0, input), "能力面判据同源");
    }

    @Test
    @DisplayName("能力面字节门:模拟插入与真实同源(都拒绝)")
    void simulate_rejectsWhenByteFull() {
        ItemStack chest = chestNearByteFull();
        LivingChestItemHandler handler = new LivingChestItemHandler(chest);

        ItemStack simulated = handler.insertItem(0, new ItemStack(Items.DIAMOND), true);

        assertEquals(1, simulated.getCount(), "模拟也必须拒绝");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest), "模拟不改内容");
    }

    @Test
    @DisplayName("字节满只挡入不挡出:超限时仍可抽取")
    void extract_stillWorksWhenByteFull() {
        ItemStack chest = chestNearByteFull();
        LivingChestItemHandler handler = new LivingChestItemHandler(chest);

        ItemStack out = handler.extractItem(0, 1, true);

        assertEquals(1, out.getCount(), "抽取不受字节门影响");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest), "模拟抽取不改内容");
    }
}
