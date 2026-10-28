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
 * 活箱子字节上限的<b>插入点精确把关</b>(2026-10-27 审查 P1；2026-10-29 收口估算路径)。
 *
 * <p><b>被守的行为</b>:两参/三参入口都必须用精确 NBT 体积。旧两参路径每非空槽固定计 64B，
 * 27 槽最多 1728B、永远碰不到 16KB ⇒ 能力面可无限塞高 NBT 物品。现在两参入口用
 * {@link RegistryAccess#EMPTY}（NeoForge 回落 BuiltInRegistries）精确计量，
 * 能力面 handler 由 {@link LivingChestItemHandlerTest} 直接守卫。</p>
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
    @DisplayName("无 registries 入口也走精确门:2 参/3 参 null 都在字节超限时拒绝")
    void noRegistriesPath_alsoRefusesWhenByteFull() {
        ItemStack chest = chestNearByteFull();
        assertTrue(LivingChestFunction.getCurrentByteUsage(chest, null)
            >= LivingChestFunction.MAX_STORAGE_BYTES, "3 参 null 也走精确回退");

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        assertFalse(LivingChestFunction.insertItem(chest, diamond),
            "2 参入口已用 RegistryAccess.EMPTY 精确计量 ⇒ 超限拒绝");
        assertEquals(20, LivingChestFunction.countUsedSlots(chest), "拒绝 ⇒ 内容不变");
        assertFalse(diamond.isEmpty(), "拒绝 ⇒ 来栈不销毁(交给调用方处理)");
    }

    @Test
    @DisplayName("回滚豁免:字节超限时 unchecked 仍收下(撤销不是新插入)")
    void rollbackPath_bypassesByteGate() {
        ItemStack chest = chestNearByteFull();

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        assertTrue(LivingChestFunction.insertItemUnchecked(chest, diamond),
            "回滚是撤销 ⇒ 不得被 16KB 禁止线挡住");
        assertEquals(21, LivingChestFunction.countUsedSlots(chest));
        assertTrue(diamond.isEmpty(), "回滚全额收下");
    }
}
