package com.qiqi.li.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 活工具动画时钟回归（2026-10-29 换源：gameTime → 本地连续时钟）。
 *
 * <p>旧实现三处都拿 {@code level.getGameTime()} 做动画：① {@code spinAngle} 的
 * {@code (now + partialTick)} 是 long+float —— gameTime 数百万 tick 后 partialTick 被
 * float 吸收 ⇒ 挖掘转圈退化成 20 Hz 整 tick 步进（周期 4~20t ⇒ 每格 18°~90°，频闪）；
 * ② gameTime 每秒被服务器时间同步包硬覆盖 ⇒ 相位随机跳 1~2 tick；③ 交互/攻击脉冲每帧
 * 拿客户端 gameTime 去减<b>服务端</b>写进组件的动作戳，6t 窗口里同步跳一下 = 进度跳 17~33%。
 * 本类钉住新时钟的三条契约：<b>转圈</b>（速度与回绕由本地时钟保证，大时钟量级不退化）、
 * <b>脉冲</b>（起点首见冻结 ⇒ 后续帧不再受 gameTime 抖动影响）、<b>簿记</b>（过窗剪枝）。</p>
 */
class LivingToolModelRendererAnimTest {

    private static final double TWO_PI = Math.PI * 2.0;

    @AfterEach
    void tearDown() {
        // 项目红线：静态状态必须显式 reset（Gradle 同一 JVM 跑全部测试）
        LivingToolModelRenderer.clearAnimationState();
    }

    // ============================================================
    // 转圈：spinAngle
    // ============================================================

    @Test
    @DisplayName("转圈：速度与回绕（周期 20 ⇒ 5t 一象限，整圈归零）")
    void spinAngle_speedAndWrap() {
        assertEquals(0.0F, LivingToolModelRenderer.spinAngle(0.0, 20), 1.0E-6F);
        assertEquals((float) (TWO_PI * 5 / 20), LivingToolModelRenderer.spinAngle(5.0, 20), 1.0E-6F,
            "5 tick = 四分之一圈");
        assertEquals(0.0F, LivingToolModelRenderer.spinAngle(20.0, 20), 1.0E-6F, "整圈回绕归零");
        assertEquals((float) (TWO_PI * 1 / 4), LivingToolModelRenderer.spinAngle(1.0, 4), 1.0E-6F,
            "最快档周期 4：1 tick = 90°");
    }

    @Test
    @DisplayName("转圈：老存档时钟量级下逐帧步进仍平滑（float 旧实现在此已整 tick 跳格）")
    void spinAngle_smoothAtHugeClock() {
        double base = 1.0E9;   // 时钟量级 1e9 tick ≈ 1585 年 —— 远超任何真实 JVM 运行时长；
                               // 旧 float 实现在 400 万 tick 量级就已被 partialTick 吸收拖垮
        double frame = 1.0 / 3.0;   // 60fps 每帧 1/3 tick
        double expect = frame / 20 * TWO_PI;
        double got = LivingToolModelRenderer.spinAngle(base + frame, 20)
            - LivingToolModelRenderer.spinAngle(base, 20);
        assertEquals(expect, got, 1.0E-4, "每帧步进 = 2π/周期 × 帧时长，与时钟量级无关");
    }

    // ============================================================
    // 脉冲：pulseElapsed（首见冻结起点）
    // ============================================================

    @Test
    @DisplayName("脉冲：首见新鲜动作从 0 起播")
    void pulseElapsed_freshAction() {
        assertEquals(0.0, LivingToolModelRenderer.pulseElapsed(1000L, 2000.0, 1000L), 1.0E-9,
            "动作戳 = 当前 gameTime ⇒ 刚出手");
    }

    @Test
    @DisplayName("脉冲：首见时按同步延迟折算起点（陈旧动作不重播）")
    void pulseElapsed_estimateAtFirstSight() {
        assertEquals(2.0, LivingToolModelRenderer.pulseElapsed(1000L, 2000.0, 1002L), 1.0E-9,
            "晚 2 tick 才看到 ⇒ 从 2t 处接着播（与旧实现同语义）");
        assertTrue(LivingToolModelRenderer.pulseElapsed(990L, 2000.0, 2000L) >= 6.0,
            "戳比 gameTime 老 ≥ 窗口 ⇒ 直接过期（陈旧动作不重播）");
    }

    @Test
    @DisplayName("脉冲：起点首见冻结 —— 后续帧 gameTime 抖动不再撕扯进度（核心回归）")
    void pulseElapsed_startFrozenAgainstSyncJitter() {
        long actionTick = 1000L;
        long jitteredGameTime = 1002L;   // 首见时的 gameTime（含 2t 延迟）
        double now0 = 2000.0;
        LivingToolModelRenderer.pulseElapsed(actionTick, now0, jitteredGameTime);

        // 之后 gameTime 被同步包拽 ±3 tick，进度仍只随本地时钟走
        double later = LivingToolModelRenderer.pulseElapsed(actionTick, now0 + 0.5, jitteredGameTime - 3);
        assertEquals(2.5, later, 1.0E-9, "0.5 tick 后 elapsed 恰好多 0.5 —— 与 gameTime 无关");
    }

    @Test
    @DisplayName("簿记：过窗起点逐帧剪枝；剪后重见同一陈旧戳重新折算仍判过期")
    void pulseElapsed_prunedAfterWindow() {
        long actionTick = 1000L;
        LivingToolModelRenderer.pulseElapsed(actionTick, 2000.0, 1000L);
        LivingToolModelRenderer.prunePulseStarts(2000.0 + 6.5);   // 过窗（> PULSE_TICKS）即清
        // 重见同一戳：重新按「当时 gameTime」折算 —— 模拟物品走出视野又回来。
        // 戳已陈旧（gameTime − 戳 = 1999t ⇒ 钳到窗口）⇒ 仍过期，不会复活旧脉冲。
        assertEquals(6.0, LivingToolModelRenderer.pulseElapsed(actionTick, 3000.0, 2999L), 1.0E-9);
    }
}
