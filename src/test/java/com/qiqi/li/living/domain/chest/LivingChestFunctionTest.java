package com.qiqi.li.living.domain.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 取消活化返还清单的堆叠倍数语义测试。
 *
 * <p>回归背景：堆叠 N 个内容相同的活箱子（组件相同才可堆叠，堆叠期间存取关闭，
 * 不变量始终成立）在取消活化时曾只掉落一份内容，N−1 份凭空蒸发。</p>
 */
class LivingChestFunctionTest {

    private static ItemStack livingChest(int count) {
        ItemStack chest = new ItemStack(Items.CHEST, count);
        LivingItemManager.setLiving(chest, true);
        return chest;
    }

    private static int totalItemCount(List<ItemStack> drops, ItemStack like) {
        int total = 0;
        for (ItemStack drop : drops) {
            if (ItemStack.isSameItemSameComponents(drop, like)) total += drop.getCount();
        }
        return total;
    }

    @Test
    @DisplayName("单个活箱子（count=1）：掉落清单 = 存储内容原样")
    void singleChest_dropsExactContents() {
        ItemStack chest = livingChest(1);
        ItemStack apples = new ItemStack(Items.APPLE, 5);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 2);
        LivingChestFunction.setItems(chest, List.of(apples, diamond));

        List<ItemStack> drops = LivingChestFunction.collectDeactivationDrops(chest);

        assertEquals(5, totalItemCount(drops, apples));
        assertEquals(2, totalItemCount(drops, diamond));
        assertEquals(2, drops.size(), "count=1 时每槽一个掉落，不拆堆");
    }

    @Test
    @DisplayName("堆叠 3 个活箱子：每槽内容 × 3 返还")
    void stackedChest_multipliesPerCopy() {
        ItemStack chest = livingChest(3);
        ItemStack apples = new ItemStack(Items.APPLE, 5);
        LivingChestFunction.setItems(chest, List.of(apples));

        List<ItemStack> drops = LivingChestFunction.collectDeactivationDrops(chest);

        assertEquals(15, totalItemCount(drops, apples));
    }

    @Test
    @DisplayName("堆叠返还超出堆叠上限：拆成多个满堆（64×3 = 192 → 3 堆）")
    void stackedOverflow_splitsIntoMaxStacks() {
        ItemStack chest = livingChest(3);
        ItemStack dirt = new ItemStack(Items.DIRT, 64);
        LivingChestFunction.setItems(chest, List.of(dirt));

        List<ItemStack> drops = LivingChestFunction.collectDeactivationDrops(chest);

        assertEquals(192, totalItemCount(drops, dirt));
        assertEquals(3, drops.size(), "192 总量应拆成 3 个 64 堆");
        for (ItemStack drop : drops) {
            assertEquals(64, drop.getCount());
        }
    }

    @Test
    @DisplayName("低堆叠上限物品（鸡蛋 16）：按 16 拆堆")
    void lowMaxStackSize_respected() {
        ItemStack chest = livingChest(2);
        ItemStack eggs = new ItemStack(Items.EGG, 16);
        LivingChestFunction.setItems(chest, List.of(eggs));

        List<ItemStack> drops = LivingChestFunction.collectDeactivationDrops(chest);

        assertEquals(32, totalItemCount(drops, eggs));
        assertEquals(2, drops.size(), "32 个鸡蛋应拆成 2 个 16 堆");
    }

    @Test
    @DisplayName("空存储 + 任意堆叠数：无掉落")
    void emptyStorage_noDrops() {
        ItemStack chest = livingChest(8);

        List<ItemStack> drops = LivingChestFunction.collectDeactivationDrops(chest);

        assertTrue(drops.isEmpty());
    }

    @Test
    @DisplayName("未活化的普通箱子：不掉落（isLivingChest 拒绝）")
    void nonLivingChest_refused() {
        ItemStack plain = new ItemStack(Items.CHEST, 2);
        plain.set(DataComponents.CONTAINER,
            ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 64))));

        List<ItemStack> drops = LivingChestFunction.collectDeactivationDrops(plain);

        assertTrue(drops.isEmpty(), "非活箱子不应产出掉落");
    }
}
