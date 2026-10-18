package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 喂锭 handler 的可测部分：规则谓词 + 矿石映射 + <b>列宽来源</b>。
 *
 * <p>⚠️ <b>柱高数值</b>仍不进单测：它依赖 {@link BeaconBaseBlocks} 的
 * {@code BEACON_BASE_BLOCKS} tag，而单测环境不加载 tag（项目既有事实：
 * {@code stack.is(TagKey)} 恒 false）⇒ 任何底座都读成 0 级，断言无意义，由游戏内验证兜底。
 * 但<b>槽位解析（宽度 / size 来源）</b>可测——2026-10-18 修的就是这一层：喂锭曾把列宽
 * 写死 9，与 tick 的 {@code getWidth()}（108 槽铁箱子 = 12 列）分叉。</p>
 *
 * <p>完整 {@code handle()} 需要 {@code ServerPlayer}，属游戏内验证。</p>
 */
class BeaconFeedHandlerTest {

    @Test
    @DisplayName("谓词：5 种矿石锭全部通过")
    void predicateAcceptsOres() {
        for (OreType ore : OreType.values()) {
            assertTrue(BeaconFeedHandler.isOreIngot(new ItemStack(ore.ingot), ItemStack.EMPTY),
                ore + " 应通过");
        }
    }

    @Test
    @DisplayName("谓词：非矿石锭 / 空栈拒绝")
    void predicateRejectsOthers() {
        assertFalse(BeaconFeedHandler.isOreIngot(new ItemStack(Items.DIRT), ItemStack.EMPTY));
        assertFalse(BeaconFeedHandler.isOreIngot(new ItemStack(Items.REDSTONE), ItemStack.EMPTY));
        assertFalse(BeaconFeedHandler.isOreIngot(new ItemStack(Items.IRON_BLOCK), ItemStack.EMPTY));
        assertFalse(BeaconFeedHandler.isOreIngot(ItemStack.EMPTY, ItemStack.EMPTY));
    }

    @Test
    @DisplayName("槽位解析：列宽取自容器上下文（108 槽铁箱子 = 12 列，不是写死的 9）")
    void columnSlots_usesContextWidth() {
        ContainerContext ctx = mock(ContainerContext.class);
        when(ctx.getSize()).thenReturn(108);
        when(ctx.getWidth()).thenReturn(12);

        int slot = 26;   // 12 列：第 3 行第 3 格；9 列：第 3 行末尾 ⇒ 两者下方一行不同
        int[] wide = BeaconFunction.resolveColumnSlots(slot, 108, 12);
        int[] narrow = BeaconFunction.resolveColumnSlots(slot, 108, 9);
        assertFalse(Arrays.equals(wide, narrow),
            "前提：该槽位在 12 列与 9 列下的映射必须不同，否则本测试无鉴别力");

        assertArrayEquals(wide, BeaconFeedHandler.columnSlotsFor(ctx, slot),
            "喂锭必须按 context.getWidth() 解析——写死 9 会与 tick 判级分叉");
    }

    @Test
    @DisplayName("槽位解析：size 取自容器上下文（越界列 −1）")
    void columnSlots_usesContextSize() {
        ContainerContext ctx = mock(ContainerContext.class);
        when(ctx.getSize()).thenReturn(20);
        when(ctx.getWidth()).thenReturn(9);

        int[] slots = BeaconFeedHandler.columnSlotsFor(ctx, 10);
        assertArrayEquals(BeaconFunction.resolveColumnSlots(10, 20, 9), slots);
        assertTrue(Arrays.stream(slots).anyMatch(s -> s == -1),
            "20 槽容器里第 2 行中间的槽位，下方一行必然有越界列");
    }

    @Test
    @DisplayName("OreType 映射：矿石锭 ↔ 效果/档位一一对应")
    void oreMapping() {
        assertEquals(OreType.IRON, OreType.fromIngot(new ItemStack(Items.IRON_INGOT)));
        assertEquals(OreType.GOLD, OreType.fromIngot(new ItemStack(Items.GOLD_INGOT)));
        assertEquals(OreType.NETHERITE, OreType.fromIngot(new ItemStack(Items.NETHERITE_INGOT)));
        assertEquals(OreType.EMERALD, OreType.fromIngot(new ItemStack(Items.EMERALD)));
        assertEquals(OreType.DIAMOND, OreType.fromIngot(new ItemStack(Items.DIAMOND)));
        assertEquals(1, OreType.IRON.unlockTier);
        assertEquals(1, OreType.GOLD.unlockTier);
        assertEquals(2, OreType.NETHERITE.unlockTier);
        assertEquals(2, OreType.EMERALD.unlockTier);
        assertEquals(3, OreType.DIAMOND.unlockTier);
    }
}
