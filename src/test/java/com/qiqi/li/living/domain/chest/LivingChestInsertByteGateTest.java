package com.qiqi.li.living.domain.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.chest.LivingChestFunction;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * 活箱子字节上限的<b>插入点精确把关</b>(2026-10-27 审查 P1)。
 *
 * <p><b>被守的行为</b>:估算路径({@code registries == null})每非空槽固定计 64B
 * ⇒ 27 槽最多 1728B,永远碰不到 16KB 上限;三参 {@code insertItem} 曾直接丢弃
 * registries 转调估算路径 ⇒ 漏斗/第三方能力面可无限塞高 NBT 物品。
 * 修复后三参版用精确 NBT 体积把关;估算路径是能力面 handler 拿不到 level 的
 * <b>已知残留缺口</b>(用例 3 钉住其现状,等 NeoForge 能力面能取到上下文再收)。</p>
 */
class LivingChestInsertByteGateTest {

    /** NeoForge 已给 {@code EMPTY} 打补丁:查不到时回落 BuiltInRegistries ⇒ 可算精确 NBT 体积。 */
    private static final Provider REGISTRIES = RegistryAccess.EMPTY;

    /** 一本 900 字符成书(精确序列化 ≈1KB,20 本即超 16KB 上限,且槽位未满)。 */
    private static ItemStack bigBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT,
            new WritableBookContent(List.of(Filterable.passThrough("x".repeat(900)))));
        return book;
    }

    /** 20 本成书 + 7 空槽 ⇒ 精确体积超上限,但<b>有空槽</b>(隔离「字节满」与「槽位满」)。 */
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
    @DisplayName("精确把关:字节已超限(但有空槽)⇒ 三参 insert 拒绝,内容不动")
    void exactGate_refusesWhenByteFull_evenWithFreeSlot() {
        ItemStack chest = chestNearByteFull();
        assertTrue(LivingChestFunction.getCurrentByteUsage(chest, REGISTRIES)
                >= LivingChestFunction.MAX_STORAGE_BYTES,
            "前置:20 本成书必须精确超限(否则本测试测不到门)");

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        assertFalse(LivingChestFunction.insertItem(chest, diamond, REGISTRIES),
            "字节超限 ⇒ 拒绝(§0 规则 2:超出拒绝插入)");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest), "拒绝 ⇒ 内容不变");
        assertFalse(diamond.isEmpty(), "拒绝 ⇒ 来栈不销毁(交给调用方处理)");
    }

    @Test
    @DisplayName("正向:未超限 ⇒ 三参 insert 正常收下")
    void exactGate_allowsWhenUnderLimit() {
        ItemStack chest = new ItemStack(Items.CHEST);
        LivingChestFunction.setItems(chest, List.of(bigBook()));

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        assertTrue(LivingChestFunction.insertItem(chest, diamond, REGISTRIES));
        assertEquals(2, LivingChestFunction.countUsedSlots(chest));
        assertTrue(diamond.isEmpty(), "插入成功 ⇒ 全额收下");
    }

    @Test
    @DisplayName("已知残留:估算路径(null registries)仍会放行超限物品 —— 能力面缺口被钉住")
    void estimatePath_stillPermits_documentedGap() {
        ItemStack chest = chestNearByteFull();

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        assertTrue(LivingChestFunction.insertItem(chest, diamond),
            "估算(27×64B)永远够不到上限 ⇒ 估算路径放行 —— 这是文档明示的已知缺口,"
                + "若此用例转红说明估算路径已被收口,请同步更新 living-chest-tech.md");
        assertTrue(LivingChestFunction.countUsedSlots(chest) >= 20);
    }
}
