package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 线圈形态检测测试（v3 —— 相位事件总线）。
 *
 * <p>验证 {@link LivingWaxedCopperFunction#getCoilForm} 正确识别各形态，
 * 以及 {@link GeneratorState} 简化后单通道的正常工作。</p>
 */
class CoilGroupingTest {

    private static ItemStack living(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    @DisplayName("涂蜡铜块：FORM_BLOCK")
    void baseCopper_formBlock() {
        assertEquals(LivingWaxedGeneratorData.FORM_BLOCK,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_COPPER_BLOCK));
        assertEquals(LivingWaxedGeneratorData.FORM_BLOCK,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_EXPOSED_COPPER));
        assertEquals(LivingWaxedGeneratorData.FORM_BLOCK,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_WEATHERED_COPPER));
        assertEquals(LivingWaxedGeneratorData.FORM_BLOCK,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_OXIDIZED_COPPER));
    }

    @Test
    @DisplayName("涂蜡雕文：FORM_CHISELED")
    void chiseled_formChiseled() {
        assertEquals(LivingWaxedGeneratorData.FORM_CHISELED,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_CHISELED_COPPER));
    }

    @Test
    @DisplayName("涂蜡切制：FORM_CUT")
    void cut_formCut() {
        assertEquals(LivingWaxedGeneratorData.FORM_CUT,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_CUT_COPPER));
    }

    @Test
    @DisplayName("涂蜡格栅：FORM_GRATE")
    void grate_formGrate() {
        assertEquals(LivingWaxedGeneratorData.FORM_GRATE,
            LivingWaxedCopperFunction.getCoilForm(Items.WAXED_COPPER_GRATE));
    }

    @Test
    @DisplayName("GeneratorState 单通道：接收事件、分域、清理")
    void generatorState_singleChannel() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);

        // 默认无域
        assertTrue(gen.channel().domains().isEmpty());

        // 接收事件后创建域
        gen.channel().onPhaseEvent(new PhaseEvent(0, 4, 0, 4096, 0), gen.preferredPeriod());
        assertEquals(1, gen.channel().domains().size());
        assertEquals(4, gen.channel().bestPeriod(4));

        // 清理（不超时，域保留）
        gen.channel().tickCleanup(10, 4);
        assertEquals(1, gen.channel().domains().size());

        // 超时后清理
        gen.channel().tickCleanup(100, 4);
        assertTrue(gen.channel().domains().isEmpty());
    }
}