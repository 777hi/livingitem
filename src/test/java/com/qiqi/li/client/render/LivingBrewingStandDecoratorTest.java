package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.brewing.LivingBrewingStandData;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 酿造白幕装饰器的纯判据单测（2026-10-12 反馈层）。
 *
 * <p>渲染本体需要 {@code GuiGraphics}（客户端），单测只钉两件可脱离 Minecraft 的事：
 * <b>守卫</b>（什么栈该画）与<b>分数</b>（{@link LivingBrewingStandDecorator#wipeFraction}）。
 * 几何与 z 语义照抄原版冷却白幕，由游戏内实测把关。</p>
 */
class LivingBrewingStandDecoratorTest {

    private static final int BREW = 400;   // BrewingBatchLogic.BREW_TIME

    private static ItemStack livingBrewing(long brewStartTick) {
        ItemStack stack = new ItemStack(Items.BREWING_STAND);
        LivingItemManager.setLiving(stack, true);
        LivingBrewingStandData.set(stack,
            LivingBrewingStandData.DEFAULT.withBrewStart(brewStartTick));
        return stack;
    }

    @Test
    @DisplayName("守卫：只有「活酿造台 + 组件记着起始时刻」才画")
    void hasActiveBrew_guard() {
        assertTrue(LivingBrewingStandDecorator.hasActiveBrew(livingBrewing(1000)),
            "批次进行中 ⇒ 画");

        assertFalse(LivingBrewingStandDecorator.hasActiveBrew(new ItemStack(Items.BREWING_STAND)),
            "原版（非活）酿造台不画 —— 装饰器按 Item 注册，所有 brewing_stand 都会进来");

        assertFalse(LivingBrewingStandDecorator.hasActiveBrew(livingBrewing(-1)),
            "活酿造台但不在酿造（brewStartTick = -1）⇒ 不画");
    }

    @Test
    @DisplayName("分数：刚开始整面白、过半剩一半、到点归零、过期与未来都 clamp")
    void wipeFraction_timeline() {
        assertEquals(1.0F, LivingBrewingStandDecorator.wipeFraction(1000, 1000), 1e-6F,
            "起始那一刻整面白（对齐原版冷却）");
        assertEquals(0.5F, LivingBrewingStandDecorator.wipeFraction(1000, 1200), 1e-6F,
            "过半剩余一半");
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(1000, 1400), 1e-6F,
            "到点（400 tick）归零 —— 与批次 20 秒同源");
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(1000, 1500), 1e-6F,
            "过期 clamp 到 0（组件忘了清也不会画错东西）");
        assertEquals(1.0F, LivingBrewingStandDecorator.wipeFraction(1000, 900), 1e-6F,
            "起始时刻在未来（时钟校正 / 重进瞬间）clamp 到 1");
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(-1, 1000), 1e-6F,
            "不在酿造（-1）⇒ 0");
    }

    @Test
    @DisplayName("分数与批次时长同源：改 BREW_TIME 白幕跟着变（防两处魔法数漂移）")
    void wipeFraction_usesBrewTimeConstant() {
        // start=0、now=BREW ⇒ 恰好归零：证明分母就是批次时长本身
        float justEnded = LivingBrewingStandDecorator.wipeFraction(0, BREW);
        assertEquals(0.0F, justEnded, 1e-6F);
        float justBefore = LivingBrewingStandDecorator.wipeFraction(0, BREW - 1);
        assertTrue(justBefore > 0.0F && justBefore <= 1.0F / BREW + 1e-6F,
            "结束前一 tick 只剩 1/400 宽");
    }
}
