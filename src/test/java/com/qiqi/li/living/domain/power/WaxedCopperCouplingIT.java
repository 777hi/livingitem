package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阶段三集成测试（v3 —— 相位事件总线）：多路振荡器 → 多发电机域独立计算。
 *
 * <p>场景：容器内多个振荡器广播 PhaseEvent，每台发电机独立分域处理。
 * 验证：不同发电机收到相同事件但各有独立域状态；同偏移在不同发电机间去重独立。</p>
 */
class WaxedCopperCouplingIT {

    @Test
    @DisplayName("2 台发电机接收相同事件：各自独立 bestFactor")
    void twoGenerators_sameEvents_independentFactors() {
        GeneratorState genA = new GeneratorState();
        genA.setPreferredPeriodFromStack(4);
        GeneratorState genB = new GeneratorState();
        genB.setPreferredPeriodFromStack(4);

        // 单路 4t 振荡器 → 两台发电机都收到
        PhaseEvent event = new PhaseEvent(0, 4, 0, 4096, 0);
        genA.channel().onPhaseEvent(event, genA.preferredPeriod());
        genB.channel().onPhaseEvent(event, genB.preferredPeriod());

        // 两台发电机应有相同 factor
        assertEquals(genA.channel().bestFactor(4), genB.channel().bestFactor(4), 1e-9);

        // 发电机 A 额外收到一路 → 其 n=2 而 B 仍为 n=1
        genA.channel().onPhaseEvent(new PhaseEvent(1, 4, 1, 4096, 0), genA.preferredPeriod());
        assertEquals(2, genA.channel().bestN(4));
        assertEquals(1, genB.channel().bestN(4));
        assertTrue(genA.channel().bestFactor(4) > genB.channel().bestFactor(4));
    }

    @Test
    @DisplayName("不同偏好周期 → 最佳域一致但解锁度不同")
    void differentPreferredPeriods() {
        GeneratorState gen4 = new GeneratorState();  // 偏好 4t
        gen4.setPreferredPeriodFromStack(4);
        GeneratorState gen8 = new GeneratorState();  // 偏好 8t
        gen8.setPreferredPeriodFromStack(8);

        // 4t 振荡器（偏移 0, 2）
        for (int t = 0; t <= 16; t += 4) {
            PhaseEvent e1 = new PhaseEvent(0, 4, 0, 4096, t);
            PhaseEvent e2 = new PhaseEvent(1, 4, 2, 4096, t + 2);
            gen4.channel().onPhaseEvent(e1, gen4.preferredPeriod());
            gen4.channel().onPhaseEvent(e2, gen4.preferredPeriod());
            gen8.channel().onPhaseEvent(e1, gen8.preferredPeriod());
            gen8.channel().onPhaseEvent(e2, gen8.preferredPeriod());
        }

        // 域状态相同
        assertEquals(4, gen4.channel().bestPeriod(4));
        assertEquals(4, gen8.channel().bestPeriod(8));
        assertEquals(2, gen4.channel().bestN(4));
        assertEquals(2, gen8.channel().bestN(8));

        // 但解锁度不同：gen4 偏好 4t → 完美调谐，gen8 偏好 8t → 失谐
        double factor4 = gen4.channel().bestFactor(4);
        double factor8 = gen8.channel().bestFactor(8);
        assertTrue(factor4 > factor8);
    }

    @Test
    @DisplayName("同偏移去重：多路同周期同偏移的振荡器只计为 1 路")
    void sameOffset_deduplication() {
        GeneratorState gen = new GeneratorState();
        gen.setPreferredPeriodFromStack(4);

        // 3 路 4t 振荡器，但偏移都是 0 → 去重后 n=1
        for (int t = 0; t <= 16; t += 4) {
            gen.channel().onPhaseEvent(new PhaseEvent(0, 4, 0, 4096, t), gen.preferredPeriod());
            gen.channel().onPhaseEvent(new PhaseEvent(1, 4, 0, 4096, t), gen.preferredPeriod());
            gen.channel().onPhaseEvent(new PhaseEvent(2, 4, 0, 1024, t), gen.preferredPeriod());
        }

        assertEquals(1, gen.channel().bestN(4));
        // |Δ| 取最大值 4096
        assertEquals(64.0, gen.channel().bestEffDeltaSum(4), 1e-9);
    }
}