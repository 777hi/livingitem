package com.qiqi.li.living.domain.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluids;

/**
 * 活海绵 tooltip 的「堆存量 / 堆容量」口径守卫（2026-11-06）。
 *
 * <p>曾经分母写死「每枚容量 64,000 mB」而分子是堆总量 × count ⇒
 * count=2 装满就显示「128000 / 64000 mB」。容量必须与 {@link SpongeItemFluidHandler}
 * 的 {@code getTankCapacity}、铜灯电量行一样随 count 线性放大。</p>
 */
class LivingSpongeTooltipTest {

    private static final LivingSpongeFunction FUNCTION = new LivingSpongeFunction();

    private static ItemStack sponge(int count, long mmB) {
        ItemStack stack = new ItemStack(Items.SPONGE, count);
        LivingItemManager.setLiving(stack, true);
        if (mmB > 0) {
            LivingSpongeData.set(stack, new LivingSpongeData(Fluids.WATER.getFluidType(), mmB));
        }
        return stack;
    }

    private static List<Component> lines(ItemStack stack) {
        List<Component> out = new ArrayList<>();
        FUNCTION.addToTooltip(Item.TooltipContext.EMPTY, out::add, TooltipFlag.NORMAL, stack);
        return out;
    }

    /** 取内容行的第 i 个参数（{@code Component.translatable} 的 args 可能是字面量或 Component）。 */
    private static long arg(Component line, int i) {
        Object[] args = assertInstanceOf(TranslatableContents.class, line.getContents()).getArgs();
        Object value = args[i];
        return Long.parseLong(value instanceof Component c ? c.getString() : String.valueOf(value));
    }

    @Test
    @DisplayName("① 单枚：堆总量 / 堆容量 = 64000 / 64000 mB")
    void singleItem_capacityIsUnitCapacity() {
        ItemStack stack = sponge(1, LivingSpongeData.MAX_MM_B);
        Component content = lines(stack).get(1);

        assertEquals("tooltip.livingitem.sponge.content",
            assertInstanceOf(TranslatableContents.class, content.getContents()).getKey());
        assertEquals(64_000L, arg(content, 1));
        assertEquals(64_000L, arg(content, 2));
    }

    @Test
    @DisplayName("② 堆叠：分子分母同乘 count，且与物品能力面逐字同口径")
    void stacked_capacityScalesWithCount() {
        for (int count : new int[] {2, 7, 64}) {
            ItemStack stack = sponge(count, LivingSpongeData.MAX_MM_B);
            Component content = lines(stack).get(1);
            long storedMb = 64_000L * count;
            long capacityMb = 64_000L * count;

            assertEquals(storedMb, arg(content, 1), "count=" + count + " 的堆总量");
            assertEquals(capacityMb, arg(content, 2), "count=" + count + " 的堆容量（分子不得超过分母）");

            SpongeItemFluidHandler handler = SpongeItemFluidHandler.of(stack);
            assertNotNull(handler);
            assertEquals(handler.getFluidInTank(0).getAmount(), arg(content, 1), "报量与能力面一致");
            assertEquals(handler.getTankCapacity(0), arg(content, 2), "容量与能力面一致");
        }
    }

    @Test
    @DisplayName("③ 半满堆叠：分母仍放大，分子保留余量感")
    void stackedHalfFull_capacityStillScaled() {
        ItemStack stack = sponge(4, LivingSpongeData.MAX_MM_B / 2);
        Component content = lines(stack).get(1);

        assertEquals(128_000L, arg(content, 1));
        assertEquals(256_000L, arg(content, 2));
        assertTrue(arg(content, 1) <= arg(content, 2));
    }

    @Test
    @DisplayName("④ 干态不产出内容行（原版海绵静态同形）")
    void dry_noContentLine() {
        assertTrue(lines(sponge(3, 0)).isEmpty());
    }
}
