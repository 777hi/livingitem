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
 * 倒桶时的<b>源格替换</b>判定（2026-10-06 A 档对齐原版）——
 * {@link LivingBucketInteractSupport#replacesResidentSource}。
 *
 * <p>对齐依据：原版倒桶（{@code BucketItem.emptyContents}）只问
 * {@code blockstate.canBeReplaced(fluid) = state.canBeReplaced() || !state.isSolid()}
 * ⇒ <b>液体块一律可替换</b>（{@code legacySolid=false}）⇒ 对水/岩浆、源/流动**一律替换**，
 * <b>原版没有「源 vs 流动」之分</b>。那个 0.444 高度门槛（{@code LavaFluid.canBeReplacedWith}）
 * 属于<b>蔓延</b>路径（能否流进这一格），倒桶不查。</p>
 *
 * <p>本接缝的职责是让<b>未覆写的流体保守拒绝</b>（default {@code false}），水与岩浆
 * 各自<b>对称</b>表态。</p>
 */
class LivingBucketInteractSupportTest {

    private static final FluidType WATER = Fluids.WATER.getFluidType();
    private static final FluidType LAVA = Fluids.LAVA.getFluidType();

    @BeforeEach
    void registerBehaviors() {
        FluidFlowBehaviors.clear();          // 静态注册表必须显式重置（项目红线）
        // 生产口径（WaterRegistration）：流动 7 + 晋升≥2 + **源格可被岩浆替换**
        FluidFlowBehaviors.register(WATER, new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return ContainerFluidData.MAX_FLOW_LEVEL; }
            @Override public int flowSpeed() { return 0; }
            @Override public boolean shouldPromote(int slot, int sourceNeighborCount) {
                return sourceNeighborCount >= 2;
            }
            @Override public boolean canBeReplacedBy(FluidType incoming) {
                return incoming == LAVA;
            }
        });
        // 生产口径：流动 3 + 永不晋升 + 焚毁/前沿反应 + **源格可被水替换**
        FluidFlowBehaviors.register(LAVA, new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return 3; }
            @Override public boolean canBeReplacedBy(FluidType incoming) {
                return incoming == WATER;
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
    @DisplayName("契约默认 false：未覆写的流体 ⇒ 源格不被替换（安全默认，供将来模组流体表态）")
    void defaultRejectsReplacement() {
        assertFalse(FluidFlowBehavior.STATIC.canBeReplacedBy(WATER),
            "default false ⇒ 保守拒绝（静止档也不覆写本接缝）");
        assertFalse(FluidFlowBehavior.STATIC.canBeReplacedBy(LAVA), "同上");
    }

    @Test
    @DisplayName("水与岩浆<b>对称</b>：岩浆源可被水替换、水源可被岩浆替换")
    void waterAndLavaAreSymmetric() {
        assertTrue(FluidFlowBehaviors.of(LAVA).canBeReplacedBy(WATER), "活水桶浇活熔岩源");
        assertTrue(FluidFlowBehaviors.of(WATER).canBeReplacedBy(LAVA), "活岩浆桶倒进活水源格");
        assertFalse(FluidFlowBehaviors.of(LAVA).canBeReplacedBy(LAVA), "同种流体不构成替换");
        assertFalse(FluidFlowBehaviors.of(WATER).canBeReplacedBy(WATER), "同种流体不构成替换");
    }

    @Test
    @DisplayName("倒桶判定：异种源 ⇒ 替换（双向）；同种源、非源格 ⇒ 不走替换分支")
    void pourDecision() {
        assertTrue(LivingBucketInteractSupport.replacesResidentSource(LAVA, WATER),
            "岩浆源 + 水 ⇒ 该格变成水源");
        assertTrue(LivingBucketInteractSupport.replacesResidentSource(WATER, LAVA),
            "水源 + 岩浆 ⇒ 该格变成岩浆源（原版同样替换）");
        assertFalse(LivingBucketInteractSupport.replacesResidentSource(LAVA, LAVA),
            "往源里倒同种流体 ⇒ 源不变（只排空桶）");
        assertFalse(LivingBucketInteractSupport.replacesResidentSource(null, WATER),
            "目标格不是源 ⇒ 由 pour 的「诞生源」分支处理，不走替换判定");
    }
}
