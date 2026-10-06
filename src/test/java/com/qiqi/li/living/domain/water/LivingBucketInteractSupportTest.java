package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * 倒桶时的<b>源格替换</b>判定（2026-10-06 B 档对齐原版）——
 * {@link LivingBucketInteractSupport#replacesResidentSource}。
 *
 * <p>对齐依据（1.21 原版 {@code Fluid.canBeReplacedWith}）：
 * <ul>
 *   <li>岩浆：{@code getHeight() >= 0.444 && incoming.is(WATER)} ⇒ 在 2D 容器里
 *       （流动 level 1~3 ⇒ 高度 0.111~0.333）<b>只有源格</b>够格；</li>
 *   <li>水：{@code direction == DOWN && !is(WATER)} ⇒ 2D 无 DOWN ⇒ <b>永不可被替换</b>
 *       ⇒ 水行为不覆写该接缝（default {@code false}）。</li>
 * </ul>
 */
class LivingBucketInteractSupportTest {

    private static final FluidType WATER = Fluids.WATER.getFluidType();
    private static final FluidType LAVA = Fluids.LAVA.getFluidType();

    @BeforeEach
    void registerBehaviors() {
        FluidFlowBehaviors.clear();          // 静态注册表必须显式重置（项目红线）
        // 水：**不覆写** canBeReplacedBy ⇒ 对齐 WaterFluid「永不可被替换」
        FluidFlowBehaviors.register(WATER, FluidFlowBehavior.flowing(
            ContainerFluidData.MAX_FLOW_LEVEL, 0));
        // 岩浆：源格可被水替换（0.444 门槛的 2D 投影）
        FluidFlowBehaviors.register(LAVA, new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return 3; }
            @Override public boolean canBeReplacedBy(FluidType incoming, boolean selfIsSource) {
                return selfIsSource && incoming == WATER;
            }
        });
    }

    @AfterEach
    void restoreBaseline() {
        FluidFlowBehaviors.clear();
        FluidFlowBehaviors.register(WATER, FluidFlowBehavior.flowing(
            ContainerFluidData.MAX_FLOW_LEVEL, 0));
    }

    @Test
    @DisplayName("契约默认 false：未覆写的流体（水）⇒ 源格永不被替换（对齐 WaterFluid）")
    void defaultRejectsReplacement() {
        assertFalse(FluidFlowBehaviors.of(WATER).canBeReplacedBy(LAVA, true),
            "水不覆写接缝 ⇒ default false ⇒ 岩浆桶倒不进水源格");
    }

    @Test
    @DisplayName("熔岩接缝：源格 + 水 ⇒ 可替换；流动格、同种流体 ⇒ 不可替换")
    void lavaReplacedOnlyByWaterAtSource() {
        var lava = FluidFlowBehaviors.of(LAVA);
        assertTrue(lava.canBeReplacedBy(WATER, true), "源格遇水 ⇒ 可被替换（浇灭矿脉）");
        assertFalse(lava.canBeReplacedBy(WATER, false), "流动格不够格（2D 里达不到 0.444 高度）");
        assertFalse(lava.canBeReplacedBy(LAVA, true), "同种流体不构成替换");
    }

    @Test
    @DisplayName("倒桶判定：岩浆源 + 水 ⇒ 替换；同种源、水源 + 岩浆 ⇒ 不替换")
    void pourDecision() {
        assertTrue(LivingBucketInteractSupport.replacesResidentSource(LAVA, WATER),
            "活水桶倒进活熔岩源格 ⇒ 该格变成水源");
        assertFalse(LivingBucketInteractSupport.replacesResidentSource(LAVA, LAVA),
            "往源里倒同种流体 ⇒ 源不变（只排空桶）");
        assertFalse(LivingBucketInteractSupport.replacesResidentSource(WATER, LAVA),
            "活岩浆桶倒不进活水源格（对齐 WaterFluid）");
        assertFalse(LivingBucketInteractSupport.replacesResidentSource(null, WATER),
            "目标格不是源 ⇒ 由 pour 的「诞生源」分支处理，不走替换判定");
    }
}
