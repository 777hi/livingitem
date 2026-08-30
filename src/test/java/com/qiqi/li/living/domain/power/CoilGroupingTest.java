package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.model.Pos2D;

/**
 * 线圈分组测试 —— 形态 = 感应拓扑（§3.4）：
 * 铜块 1 组×4 向、雕文 2 组（V/H 隔离）、切制 1 组×1 向。
 */
class CoilGroupingTest {

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("涂蜡铜块：1 通道 × 4 向，每方向直连+感应共 8 路")
    void baseCopper_fullOverpass() {
        GeneratorState gen = new GeneratorState();
        LivingWaxedCopperFunction.configureCoils(gen, living(Items.WAXED_COPPER_BLOCK, 1));

        assertEquals(1, gen.channelCount());
        for (int d = 0; d < 4; d++) {
            assertEquals(0, gen.dirChannel(d));
        }
        assertEquals(8, gen.channel(0).pathCount());
    }

    @Test
    @DisplayName("涂蜡雕文：2 通道（V={UP,DOWN}，H={LEFT,RIGHT}）轴间隔离")
    void chiseled_dualAxis() {
        GeneratorState gen = new GeneratorState();
        LivingWaxedCopperFunction.configureCoils(gen, living(Items.WAXED_CHISELED_COPPER, 1));

        assertEquals(2, gen.channelCount());
        assertEquals(0, gen.dirChannel(0));   // UP → V
        assertEquals(0, gen.dirChannel(1));   // DOWN → V
        assertEquals(1, gen.dirChannel(2));   // LEFT → H
        assertEquals(1, gen.dirChannel(3));   // RIGHT → H
        assertEquals(4, gen.channel(0).pathCount());
        assertEquals(4, gen.channel(1).pathCount());
    }

    @Test
    @DisplayName("涂蜡切制：1 通道 × 1 向（WASD 配置），其余方向不感知")
    void cut_singleDirection() {
        ItemStack stack = living(Items.WAXED_CUT_COPPER, 1);
        LivingItemManager.setWaxedCutData(stack, new LivingWaxedCutData(Pos2D.RIGHT));

        GeneratorState gen = new GeneratorState();
        LivingWaxedCopperFunction.configureCoils(gen, stack);

        assertEquals(1, gen.channelCount());
        assertEquals(0, gen.dirChannel(3));   // RIGHT 感知
        assertEquals(-1, gen.dirChannel(0));  // UP 不感知
        assertEquals(-1, gen.dirChannel(1));
        assertEquals(-1, gen.dirChannel(2));
        assertEquals(2, gen.channel(0).pathCount());   // 1 直连 + 1 感应
    }

    @Test
    @DisplayName("配置变化时重建通道（波形状态重置，重新起振）")
    void configChange_rebuildsChannels() {
        GeneratorState gen = new GeneratorState();
        ItemStack stack = living(Items.WAXED_CUT_COPPER, 1);

        LivingItemManager.setWaxedCutData(stack, new LivingWaxedCutData(Pos2D.RIGHT));
        LivingWaxedCopperFunction.configureCoils(gen, stack);
        gen.channel(0).onPathValue(0, 0, 100);
        gen.channel(0).path(0).recordValue(1, 100);

        LivingItemManager.setWaxedCutData(stack, new LivingWaxedCutData(Pos2D.LEFT));
        LivingWaxedCopperFunction.configureCoils(gen, stack);

        assertEquals(0.0, gen.channel(0).path(0).periodTicks(), 1e-9);
        assertTrue(!gen.channel(0).path(0).hasUsablePhase());
    }
}
