package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;

/**
 * 活耕地种子图标装饰器的守卫回归（2026-09-16）。
 *
 * <p>装饰器按 {@code Item} 注册 ⇒ 会对<b>所有</b> {@code Items.FARMLAND}
 * （含普通非活耕地）调用，守卫写错会让普通耕地也叠上种子图标。这里只钉住
 * {@link LivingFarmlandSeedDecorator#shouldRenderSeed} 这条纯逻辑，
 * 画面本身交游戏实测（客户端渲染无法单测）。</p>
 */
class LivingFarmlandSeedDecoratorTest {

    /** 活耕地（带 IS_LIVING；planted=true 时再写种植组件） */
    private static ItemStack livingFarmland(boolean planted) {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        if (planted) {
            LivingItemManager.setFarmlandPlant(stack, new FarmlandPlantComponent(
                Items.WHEAT_SEEDS, 3, 7, 0L, -1, List.of()));
        }
        return stack;
    }

    @Test
    @DisplayName("普通（非活）耕地 → 不画")
    void plainFarmland_noIcon() {
        assertFalse(LivingFarmlandSeedDecorator.shouldRenderSeed(new ItemStack(Items.FARMLAND)),
            "装饰器会对所有 FARMLAND 调用，普通耕地必须不画");
    }

    @Test
    @DisplayName("活耕地但未种植 → 不画")
    void livingUnplanted_noIcon() {
        assertFalse(LivingFarmlandSeedDecorator.shouldRenderSeed(livingFarmland(false)),
            "没有作物就没有种子图标可画");
    }

    @Test
    @DisplayName("活耕地 + 已种植 → 画（本次修复的触发条件）")
    void livingPlanted_drawsIcon() {
        assertTrue(LivingFarmlandSeedDecorator.shouldRenderSeed(livingFarmland(true)),
            "已种植的活耕地应当叠加种子图标");
    }

    @Test
    @DisplayName("非耕地的活物品（即使带种植组件）→ 不画")
    void livingNonFarmland_noIcon() {
        ItemStack dirt = new ItemStack(Items.DIRT, 1);
        LivingItemManager.setLiving(dirt, true);
        LivingItemManager.setFarmlandPlant(dirt, new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 3, 7, 0L, -1, List.of()));

        assertFalse(LivingFarmlandSeedDecorator.shouldRenderSeed(dirt),
            "不是耕地就不该有耕地槽渲染");
    }
}
