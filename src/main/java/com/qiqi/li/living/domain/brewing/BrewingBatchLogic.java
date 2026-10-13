package com.qiqi.li.living.domain.brewing;

import java.util.Optional;

import net.minecraft.world.item.Item;

/**
 * 活酿造台的<b>批次状态机（纯逻辑）</b> —— 不碰世界，只做状态转移 ⇒ 可单测。
 *
 * <p>世界侧（{@code LivingBrewingStandFunction.tick}）负责：解析槽位、读实时槽内容、
 * 把 {@link Step} 应用到槽位与组件。本类只回答「这一 tick 该发生什么」。</p>
 *
 * <h3>与原版的一一对应</h3>
 * <p>原版 {@code BrewingStandBlockEntity.serverTick} 的核心是三段：</p>
 * <pre>
 * if (brewTime &gt; 0) {
 *     brewTime--;
 *     if (brewTime == 0 &amp;&amp; canBrew) doBrew();
 *     else if (!canBrew || 材料的 Item 变了) brewTime = 0;
 * } else if (canBrew &amp;&amp; fuel &gt; 0) {
 *     fuel--; brewTime = 400; ingredient = 当前材料;
 * }
 * </pre>
 * <p>本类把它原样搬过来，只把「−1」换成「−step」（{@code step = 堆叠数}，D11）。
 * ⚠️ <b>结构与判据顺序完全照抄</b> —— 包括「完成那一 tick 不再检查材料变化」这个细节
 * （原版是 {@code else if} ⇒ 命中完成分支就不会再判材料）。</p>
 *
 * <h3>作废判据（D8，只有两条）</h3>
 * <ol>
 *   <li><b>不可酿造</b>（{@code canBrew} 为假）：材料槽空 / 材料非有效材料 /
 *       3 瓶里没有任何一个能配</li>
 *   <li><b>材料的 {@code Item} 变了</b> —— ⚠️ 只比 {@code Item}，<b>不比数量 / 内容</b></li>
 * </ol>
 * <p>⚠️ <b>瓶子完全不参与判据</b>（原版只记录材料）⇒ 中途换瓶子不作废（设计稿 §4.6）。</p>
 */
public final class BrewingBatchLogic {

    /** 一个批次的倒计时长度（对齐原版 400 tick = 20 秒）。 */
    public static final int BREW_TIME = 400;

    /** 1 个烈焰粉的可用次数（对齐原版 `FUEL_USES = 20`）。 */
    public static final int FUEL_USES = 20;

    private BrewingBatchLogic() {}

    /**
     * 堆叠加速：<b>堆叠多少，每 tick 就推进多少 tick</b>（D11）。
     *
     * <p>唯一「过原点、斜率 1」的线性 ⇒ 单个 = 原版 400t；N 个 = N 倍速。
     * 不需要 `maxStack`、不需要封顶（每 tick 只检查一次完成 ⇒ 速度天然封在 ≈1 批/tick）。</p>
     */
    public static int stepFor(int stackCount) {
        return Math.max(1, stackCount);
    }

    /** 一次 tick 的状态转移结果。 */
    public sealed interface Step {

        /** 无可酿造条件、也不在酿造中 ⇒ 什么都不做。 */
        record Idle() implements Step {}

        /** 继续倒计时（尚未完成）。 */
        record Continue(int brewTime) implements Step {}

        /** 作废：倒计时归零。燃料不退、材料不扣（D7 / D8）。 */
        record Abort() implements Step {}

        /** 完成：扣 1 份材料 + 转化最多 3 瓶（D7）。 */
        record Complete() implements Step {}

        /** 启动新批次：<b>已扣 1 次燃料</b>（调用方负责写回 fuel）。 */
        record Start(int brewTime, Item ingredient) implements Step {}
    }

    /**
     * 推进一步。
     *
     * @param brewTime          当前倒计时（0 = 不在酿造中）
     * @param batchIngredient   本批次启动时记录的材料 {@code Item}（空 = 无记录）
     * @param canBrew           本 tick 实时判定的「可酿造」（材料有效 + 至少 1 瓶能配）
     * @param currentIngredient 当前材料槽的 {@code Item}；材料槽为空时传 {@code null}
     * @param fuel              当前燃料次数（<b>已含本 tick 的「补燃料」结果</b>）
     * @param step              每 tick 推进量（见 {@link #stepFor}）
     */
    public static Step advance(int brewTime, Optional<Item> batchIngredient,
                               boolean canBrew, Item currentIngredient,
                               int fuel, int step) {
        if (brewTime > 0) {
            int next = brewTime - step;
            // ① 命中完成（原版 brewTime == 0 && flag）—— 注意：此分支不再检查材料变化（照抄原版）
            if (next <= 0 && canBrew) {
                return new Step.Complete();
            }
            // ② 作废（原版 else if (!flag || 材料的 Item 变了)）
            if (!canBrew || !sameItem(batchIngredient, currentIngredient)) {
                return new Step.Abort();
            }
            return new Step.Continue(next);
        }
        // ③ 启动（原版 else if (flag && fuel > 0)）
        if (canBrew && fuel > 0) {
            return new Step.Start(BREW_TIME, currentIngredient);
        }
        return new Step.Idle();
    }

    /**
     * 状态机结果 → <b>新的批次标记</b>（白幕用；纯函数供单测）。
     *
     * <p>语义：标记<b>只在批次进行中有效</b> —— {@code Start} 写当前时刻、{@code Continue} 保持；
     * {@code Complete} / {@code Abort} / {@code Idle} <b>一律收起</b>（{@code -1}）。</p>
     *
     * <p>⚠️ <b>{@code Idle} 也收起是「自愈」</b>（2026-10-13 修，用户实测「没有进度时白幕还在播放」）：
     * 物品被移出容器后，运行时缓存按方案 A 作废（{@code brewTime} 归零）⇒ 状态机再也不会走到
     * {@code Complete}/{@code Abort}（那两个分支要求 {@code brewTime > 0}）⇒ 组件里的标记会
     * <b>永久残留</b>，而客户端拿它当「批次在跑」的凭据 ⇒ 白幕继续播完一轮。
     * ⇒ 无批次即清标记（只在「从有到无」时写一次，稳态零写入）。</p>
     *
     * @param step    本 tick 的状态转移结果
     * @param current 组件里现有的标记（{@code -1} = 无）
     * @param now     当前 {@code level.getGameTime()}（{@code Start} 时写入）
     */
    public static long batchMarkAfter(Step step, long current, long now) {
        return switch (step) {
            case Step.Start ignored -> now;
            case Step.Continue ignored -> current;
            default -> -1L;   // Complete / Abort / Idle ⇒ 收起
        };
    }

    /** ⚠️ 只比 {@code Item} 身份，不比数量 / 内容（D8）。 */
    private static boolean sameItem(Optional<Item> batch, Item current) {
        return batch.isPresent() && batch.get() == current;
    }
}
