package com.qiqi.li.living.domain.water;

/**
 * 流体流动行为（1b-2 契约）—— 框架向流体侧要的「行为参数」。
 *
 * <p>容器内流体引擎（{@link ContainerFluidData}）<b>不认具体是水还是岩浆</b>，
 * 只通过本接口问「这个流体怎么流」。⇒ 新增流体 <b>零改引擎</b>，只需注册一个行为。</p>
 *
 * <h3>行为分档</h3>
 * <ul>
 *   <li>{@link #canFlow()} —— 会流动 / 静止。静止流体（很多模组流体）<b>只做源、不扩散</b>。</li>
 *   <li>{@link #maxLevel()} —— <b>level 上限 = 流动距离</b>（水 7 / 岩浆 3，各自不同）。</li>
 *   <li>{@link #flowSpeed()} —— 流速（扩散节拍）。⚠️ <b>预留字段</b>，当前用途不明，先加上。</li>
 * </ul>
 *
 * <p>⚠️ <b>归属</b>：本接口与 {@link FluidFlowBehaviors} 是<b>框架提供的契约</b>；
 * 具体流体的取值（水 / 岩浆 / 模组流体）由<b>流体侧</b>注册。</p>
 */
public interface FluidFlowBehavior {

    /** 静止流体：只做源、不扩散（未注册流体的默认行为）。 */
    FluidFlowBehavior STATIC = new FluidFlowBehavior() {
        @Override public boolean canFlow() { return false; }
        @Override public int maxLevel() { return 0; }
        @Override public int flowSpeed() { return 0; }
    };

    /**
     * 会流动的流体。
     *
     * @param maxLevel  level 上限（流动距离）
     * @param flowSpeed 流速（预留；0 = 即时）
     */
    static FluidFlowBehavior flowing(int maxLevel, int flowSpeed) {
        return new FluidFlowBehavior() {
            @Override public boolean canFlow() { return true; }
            @Override public int maxLevel() { return maxLevel; }
            @Override public int flowSpeed() { return flowSpeed; }
        };
    }

    /** 是否会流动（false = 静止，只做源、不进扩散）。 */
    boolean canFlow();

    /** level 上限（流动距离）。 */
    int maxLevel();

    /** 流速（扩散节拍）。⚠️ 预留，当前未使用。 */
    int flowSpeed();
}
