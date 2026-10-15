package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 喂锭 handler 的可测部分：规则谓词 + 矿石映射。
 *
 * <p>⚠️ <b>等级读取路径（{@code readPyramidLevel}）不进单测</b>：它依赖
 * {@link BeaconBaseBlocks} 的 {@code BEACON_BASE_BLOCKS} tag，而单测环境不加载 tag
 * （项目既有事实：{@code stack.is(TagKey)} 恒 false）⇒ 任何底座都读成 0 级，
 * 断言无意义。该路径由游戏内验证兜底；其纯槽位数学已由 {@code BeaconColumnSlotsTest}
 * 覆盖（{@code resolveColumnSlots} 与 {@code readPyramidLevel} 共用同一套偏移逻辑）。</p>
 *
 * <p>完整 {@code handle()} 需要 {@code ServerPlayer}，同样属游戏内验证。</p>
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
