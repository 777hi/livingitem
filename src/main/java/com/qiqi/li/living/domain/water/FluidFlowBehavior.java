package com.qiqi.li.living.domain.water;

import net.minecraft.world.item.ItemStack;

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

    /**
     * 流速（扩散节拍，2026-10-05 契约消费）：
     * {@code -1}（默认）= **派生自原版** {@code Fluid.getTickDelay}（水 5 / 岩浆 30，下界 10）；
     * {@code 0} = 瞬时（实际层直接取目标层，无生长过程）；
     * {@code N} = 每 N tick 实际层向目标推进一格。
     */
    default int flowSpeed() {
        return -1;
    }

    // ── 引擎接缝（1b-2，默认 no-op；流体侧填行为）────────────────

    /**
     * 晋升判定（引擎在 {@code recalculate} 的<b>收敛循环</b>里调用）：该格是否应<b>升格为源</b>。
     *
     * <p>默认 {@code false}（不晋升）。框架负责算「该格四邻中已是源的个数」并反复收敛
     * （升格后水网扩张会重跑 BFS，直到无新升格）；具体阈值由流体侧填
     * （水：≥2 邻源；岩浆：永不）。升格写入容器的派生源集合，成为永久资产。</p>
     *
     * @param slot                候选格（当前是流动格，非源）
     * @param sourceNeighborCount 该格四邻中已是源的个数
     */
    default boolean shouldPromote(int slot, int sourceNeighborCount) {
        return false;
    }

    /**
     * 转化判定（引擎<b>每流体拍</b>在源格调用）：源格上的物品是否应<b>转化</b>。
     *
     * <p>默认 {@code null}（不转化）。转化表归流体侧
     * （空桶→水桶、干海绵→湿海绵、混凝土粉末→混凝土…）。引擎只在<b>源格</b>问，非源格不问。</p>
     *
     * @param item 源格上的物品（保证非空）
     * @return 转化后的物品；{@code null} = 不转化（物品原样保留）
     */
    default ItemStack transformItem(ItemStack item) {
        return null;
    }

    /**
     * 该转化是否<b>消耗源</b>（引擎在转化成功后移除该格派生源；2026-10-06 实测口径：
     * 空桶→水桶必须消耗源，否则一格水 = 无限水桶）。默认 {@code false}（催化剂型，
     * 如混凝土粉末→混凝土——源保留，工厂化转化）。
     *
     * <p>仅在 {@link #transformItem} 返回非空后询问（入参保证非空且非活物品）。</p>
     */
    default boolean consumesSourceOnTransform(ItemStack input) {
        return false;
    }
}
