package com.qiqi.li.living.domain.water;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidType;

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
     * **源格替换**判定（2026-10-06 B/A 档对齐原版）：倒桶时目标格<b>已是源</b>且流体与倒进来的
     * <b>不同种</b>时，询问该源是否允许被替换 —— 允许则<b>同键改写源类型</b>
     * （水倒进岩浆源格 ⇒ 该格变成水源，原岩浆源湮灭；反向亦然）。
     *
     * <p>🔴 <b>对齐依据（更正早期误读）</b>：原版倒桶（{@code BucketItem.emptyContents}）
     * <b>不查</b> {@code Fluid.canBeReplacedWith}，只问
     * {@code blockstate.canBeReplaced(fluid) = state.canBeReplaced() || !state.isSolid()}
     * ⇒ <b>液体块一律可替换</b>（{@code legacySolid=false}）⇒ 倒桶对水/岩浆、源/流动
     * <b>一律替换</b>。原版<b>没有</b>「源格 vs 流动格」的区分 —— 那个 0.444 高度门槛
     * （{@code LavaFluid.canBeReplacedWith}）属于<b>蔓延</b>路径（能否流进这一格），
     * 在 2D 容器里流动 level 1~3恒不达标。</p>
     *
     * <p>本接缝的意义是<b>让未覆写的流体保守拒绝</b>（default {@code false}），
     * 而不是复刻原版那个「液体一律可替换」的方块规则 —— 未来接入模组流体时，
     * 由它各自表态是否允许被倒进来的流体顶替。</p>
     *
     * <p>⚠️ 只用于<b>源格</b>；倒进任何<b>流动</b>格一律直接诞生源（覆盖），与原版一致。</p>
     *
     * @param incoming 倒进来的流体类型
     */
    default boolean canBeReplacedBy(FluidType incoming) {
        return false;
    }

    // ── 活熔岩接缝（2026-10-05/06，流体侧填行为）────────────────

    /**
     * 焚毁判定（引擎每 tick 对该流体各格上的非活物品询问）：
     * {@link IncinerateResult#SURVIVE} 存活共存、{@link IncinerateResult#BURN} 焚毁、
     * {@link IncinerateResult#SPAWN_SOURCE} 消耗物品并在该格诞生一个本流体源（新配方）。
     * 默认 {@code SURVIVE}（水等流体不焚毁）。
     */
    default IncinerateResult incinerateResult(ItemStack input) {
        return IncinerateResult.SURVIVE;
    }

    /**
     * 前沿反应（引擎在<b>流动格</b>四邻出现异种流体时询问）：
     * 返回产物物品（落在该格、流体退去），{@code null} = 不反应（正常流入）。
     * 默认 {@code null}。活熔岩对水：圆石（原版 shouldSpreadLiquid 的容器映射）。
     */
    default ItemStack frontierReaction(FluidType neighborFluid) {
        return null;
    }

    /**
     * 前沿反应·<b>源格</b>版本（2026-10-06 黑曜石循环）：引擎在<b>源格</b>四邻出现异种流体时
     * 优先问本方法；产物落在该格、流体退去且<b>源湮灭</b>。{@code null} = 不反应。
     *
     * <p>默认<b>回退</b> {@link #frontierReaction} ⇒ 不区分源/流动的流体<b>零变化</b>
     * （对齐原版 {@code shouldSpreadLiquid}：本格是源 ⇒ 黑曜石，流动 ⇒ 圆石 —— 与「谁撞谁」无关）。
     * 活熔岩：源 + 水 ⇒ 黑曜石。</p>
     */
    default ItemStack frontierSourceReaction(FluidType neighborFluid) {
        return frontierReaction(neighborFluid);
    }

    /** 焚毁/源诞生的判定结果（机制一/四）。 */
    enum IncinerateResult { SURVIVE, BURN, SPAWN_SOURCE }
}
