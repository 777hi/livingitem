package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.brewing.BrewingBatchLogic;
import com.qiqi.li.living.domain.brewing.LivingBrewingStandData;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 酿造白幕装饰器的纯判据单测（2026-10-13 改为「本地动画」后）。
 *
 * <p>渲染本体需要 {@code GuiGraphics}（客户端），单测钉四件可脱离 Minecraft 的事：
 * <b>守卫</b>（{@link LivingBrewingStandDecorator#activeBatchMark}）、
 * <b>时长</b>（{@link LivingBrewingStandDecorator#durationFor}）、
 * <b>比例</b>（{@link LivingBrewingStandDecorator#wipeFraction}）、
 * <b>动画状态</b>（{@link LivingBrewingStandDecorator#animationStart} 的「首次记起点 / 播完不删 / 新批次新起点」）。
 * 几何与 z 语义照抄原版冷却白幕，由游戏内实测把关。</p>
 */
class LivingBrewingStandDecoratorTest {

    private static final int BREW = BrewingBatchLogic.BREW_TIME;

    @AfterEach
    void tearDown() {
        // 项目红线：静态状态必须显式 reset（Gradle 同一 JVM 跑全部测试）
        LivingBrewingStandDecorator.clearAnimationState();
    }

    private static ItemStack livingBrewing(long brewStartTick) {
        ItemStack stack = new ItemStack(Items.BREWING_STAND);
        LivingItemManager.setLiving(stack, true);
        LivingBrewingStandData.set(stack,
            LivingBrewingStandData.DEFAULT.withBrewStart(brewStartTick));
        return stack;
    }

    // ============================================================
    // 守卫
    // ============================================================

    @Test
    @DisplayName("守卫：只有「活酿造台 + 组件记着批次标记」才画")
    void activeBatchMark_guard() {
        assertEquals(1000L, LivingBrewingStandDecorator.activeBatchMark(livingBrewing(1000)),
            "批次进行中 ⇒ 返回标记");

        assertEquals(-1L, LivingBrewingStandDecorator.activeBatchMark(new ItemStack(Items.BREWING_STAND)),
            "原版（非活）酿造台不画 —— 装饰器按 Item 注册，所有 brewing_stand 都会进来");

        assertEquals(-1L, LivingBrewingStandDecorator.activeBatchMark(livingBrewing(-1)),
            "活酿造台但不在酿造（brewStartTick = -1）⇒ 不画");
    }

    // ============================================================
    // 时长：堆叠折算
    // ============================================================

    @Test
    @DisplayName("时长：单堆 = 400 tick；N 堆 = 400/N（与服务端 stepFor 同口径）")
    void durationFor_stackScaling() {
        assertEquals(400.0F, LivingBrewingStandDecorator.durationFor(1), 1e-6F, "单堆 = 原版 20 秒");
        assertEquals(200.0F, LivingBrewingStandDecorator.durationFor(2), 1e-6F, "2 堆 = 半程");
        assertEquals(100.0F, LivingBrewingStandDecorator.durationFor(4), 1e-6F, "4 堆 = 1/4 程");
        assertEquals(400.0F, LivingBrewingStandDecorator.durationFor(0), 1e-6F,
            "0 堆是退化输入 ⇒ 按单堆（BrewingBatchLogic.stepFor 同款保护）");
        assertEquals(400.0F, LivingBrewingStandDecorator.durationFor(-3), 1e-6F, "负堆同理");
    }

    @Test
    @DisplayName("时长与服务端加速同源：改 BREW_TIME / stepFor 口径，两边一起变（防漂移）")
    void durationFor_sameSourceAsStepFor() {
        for (int count = 1; count <= 8; count++) {
            float expected = BREW / (float) BrewingBatchLogic.stepFor(count);
            assertEquals(expected, LivingBrewingStandDecorator.durationFor(count), 1e-6F,
                "堆叠 " + count + " 的动画时长必须等于「基准时长 / 每 tick 推进量」");
        }
    }

    // ============================================================
    // 比例
    // ============================================================

    @Test
    @DisplayName("比例：起点整面白、过半剩一半、走完归零、超出与负值都 clamp")
    void wipeFraction_timeline() {
        assertEquals(1.0F, LivingBrewingStandDecorator.wipeFraction(0.0F, BREW), 1e-6F,
            "动画起点整面白（对齐原版冷却）");
        assertEquals(0.5F, LivingBrewingStandDecorator.wipeFraction(BREW / 2.0F, BREW), 1e-6F,
            "过半剩余一半");
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(BREW, BREW), 1e-6F,
            "走完 400 tick ⇒ 归零（与批次 20 秒同源）");
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(BREW * 2.0F, BREW), 1e-6F,
            "播完之后（条目还在）⇒ 保持不画");
        assertEquals(1.0F, LivingBrewingStandDecorator.wipeFraction(-5.0F, BREW), 1e-6F,
            "负 elapsed（理论上不出现）clamp 到整面白，不画反");
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(10.0F, 0.0F), 1e-6F,
            "时长为 0（退化）⇒ 不画，不除零");
    }

    // ============================================================
    // 动画状态：本地起点 / 播完不删 / 新批次重播
    // ============================================================

    @Test
    @DisplayName("动画起点：首次见到标记记为当下；之后同一标记不再改（本地时钟自演）")
    void animationStart_firstSeenOnly() {
        float start = LivingBrewingStandDecorator.animationStart(500L, 1000.0F);
        assertEquals(1000.0F, start, 1e-6F, "首次见到 ⇒ 起点 = 当下");

        float later = LivingBrewingStandDecorator.animationStart(500L, 1234.0F);
        assertEquals(1000.0F, later, 1e-6F, "同一标记再来（哪怕过了很多 tick）⇒ 起点不变，动画按本地时钟继续走");
    }

    @Test
    @DisplayName("重播：新批次 = 新标记 = 新起点 ⇒ 白幕重新满格（无需额外逻辑）")
    void animationStart_newBatchRestarts() {
        LivingBrewingStandDecorator.animationStart(500L, 1000.0F);
        // 上一批还没播完（1000 → 1200 只走了 200/400），服务端开了新批次 ⇒ 标记变 800
        float restart = LivingBrewingStandDecorator.animationStart(800L, 1200.0F);
        assertEquals(1200.0F, restart, 1e-6F, "新标记 ⇒ 新起点 = 当下");
        assertEquals(1.0F, LivingBrewingStandDecorator.wipeFraction(1200.0F - restart, BREW), 1e-6F,
            "⇒ 白幕回到满格重新消退（这就是「没播完也重新播放」）");
    }

    @Test
    @DisplayName("播完不删：条目留在 map 里当「播过」的戳，避免播完被重建而反复重播")
    void animationStart_keptAfterFinish() {
        LivingBrewingStandDecorator.animationStart(500L, 1000.0F);
        // 播完（>400 tick 后）再取 —— 起点仍是老的 ⇒ 比例保持 0，不会重播
        float start = LivingBrewingStandDecorator.animationStart(500L, 2000.0F);
        assertEquals(1000.0F, start, 1e-6F);
        assertEquals(0.0F, LivingBrewingStandDecorator.wipeFraction(2000.0F - start, BREW), 1e-6F);
    }

    @Test
    @DisplayName("跨存档清理：清空后同标记会重新起播（新世界不该继承旧动画状态）")
    void clearAnimationState_resets() {
        LivingBrewingStandDecorator.animationStart(500L, 1000.0F);
        LivingBrewingStandDecorator.clearAnimationState();
        float after = LivingBrewingStandDecorator.animationStart(500L, 3000.0F);
        assertEquals(3000.0F, after, 1e-6F, "清空后同标记视为首次见到 ⇒ 起点 = 当下");
        assertTrue(LivingBrewingStandDecorator.wipeFraction(0.0F, BREW) > 0.0F);
    }
}
