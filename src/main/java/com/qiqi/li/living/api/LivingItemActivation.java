package com.qiqi.li.living.api;

import javax.annotation.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 活化门面 —— 所有「活化 / 取消活化」途径的<b>统一判定点</b>（D1，2026-09-27）。
 *
 * <h3>为什么要有这个类</h3>
 * <p>活化有多个途径，判定如果散在各个入口里，未来每加一个途径都要记得补判定，迟早漏 ⇒ 收口到这里。</p>
 *
 * <table>
 *   <caption>途径与是否受约束</caption>
 *   <tr><th>途径</th><th>入口</th><th>受约束？</th></tr>
 *   <tr><td>{@link Via#PLAYER}</td><td>玩家点活按钮（{@code LivingTagPacket}）</td><td>✅ 是（本功能要封的就是它）</td></tr>
 *   <tr><td>{@link Via#EXTERNAL}</td><td>任务奖励 / 命令给予 / 掉落</td><td>❌ 否 —— <b>这是配置者主动发放活物品的渠道</b></td></tr>
 *   <tr><td>{@link Via#INTERNAL}</td><td>模组内部产出（活耕地、活地图）</td><td>❌ 否 —— 禁了功能就坏了</td></tr>
 * </table>
 *
 * <h3>判定顺序</h3>
 * <ol>
 *   <li><b>JSON 规则</b>（{@link ActivationRuleConfig}）—— 黑名单 / 白名单 / 按途径，
 *       由配置者显式书写；无规则时默认全放行。</li>
 *   <li><b>拒绝「无功能认领」的活化</b>（{@code options.denyUnclaimed}）——
 *       ⚠️ <b>默认关闭</b>（2026-09-27 用户定调：默认行为不可动，原本任何物品都能活化）。
 *       开启后，活化一个没有任何 {@link LivingItemFunction} 认领的物品会被拒绝 ——
 *       它只会进入活物品隔离（不传输 / 不熔炼 / 不作燃料）而零收益（<b>P1-3</b> 的本体）。
 *       是否值得开启由配置者自己权衡。</li>
 * </ol>
 *
 * <p>设计稿见 {@code docs/buffer/activation-rule-design.md}。</p>
 */
public final class LivingItemActivation {

    private LivingItemActivation() {}

    /** 活化途径 —— 决定一条规则是否作用于这次活化。 */
    public enum Via {
        /** 玩家点击活按钮发起 */
        PLAYER,
        /** 任务奖励 / 命令给予 / 掉落等外部来源 */
        EXTERNAL,
        /** 模组内部逻辑产出（如活锄头耕地产出活耕地） */
        INTERNAL,
    }

    /** 判定结果（供调用方给出准确提示）。 */
    public enum Result {
        /** 允许 */
        ALLOW,
        /** 被规则禁止（JSON 规则，尚未实现） */
        DENY_RULE,
        /** 没有任何功能会认领它 —— 活化只有副作用、零收益 */
        DENY_UNCLAIMED,
    }

    /**
     * 判断这次活化是否允许。
     *
     * @param stack    目标物品
     * @param activate true = 活化；false = 取消活化
     * @param via      发起途径
     */
    public static Result evaluate(ItemStack stack, boolean activate, Via via) {
        // ① JSON 规则（黑名单 / 白名单 / 按途径）
        if (ActivationRuleConfig.evaluate(stack, activate, via) == ActivationRuleConfig.Action.DENY) {
            return Result.DENY_RULE;
        }
        // ② 无功能认领 —— 活化它只有隔离副作用、零收益
        //    （只对活化生效；取消活化无副作用）
        if (activate && ActivationRuleConfig.isDenyUnclaimed()
                && !LivingItemManager.hasAnyFunctionFor(stack)) {
            return Result.DENY_UNCLAIMED;
        }
        return Result.ALLOW;
    }

    /** 便捷方法：只要{@link Result#ALLOW}才返回 true。 */
    public static boolean isAllowed(ItemStack stack, boolean activate, Via via) {
        return evaluate(stack, activate, via) == Result.ALLOW;
    }

    // ==================================================================
    // 活化执行（2026-10-04）—— 所有「切换活物品状态」的唯一入口
    //
    // ⭐ 为什么判定（evaluate）与执行（apply）分成两个方法：
    //    判定关心**配置意图**（黑白名单），执行关心**数据安全**（派发/否决）。
    //    ⚠️ 判定**故意不收进这里** —— INTERNAL 途径（活耕地、活地图）按设计不受约束
    //    （见本类 javadoc 的途径表），把它包进来会让「规则拦掉内部产出」成为可能，
    //    而内部产出被拦时没有任何玩家能收到提示 ⇒ 静默失败。
    // ==================================================================

    /**
     * 切换活物品状态，并向「认领该物品的功能」派发时机钩子。
     *
     * <p><b>顺序是硬约束</b>（违反即 bug）：</p>
     * <table border="1">
     *   <caption>两个方向的调用顺序与原因</caption>
     *   <tr><th>方向</th><th>顺序</th><th>反了会怎样</th></tr>
     *   <tr><td>活化</td><td>{@code setLiving(true)} → {@code onActivated}</td>
     *       <td>功能认不出物品（判据含 {@code isLivingItem}）</td></tr>
     *   <tr><td>取消活化</td><td>{@code onDeactivated} → {@code setLiving(false)}</td>
     *       <td>箱子不掉物、末影箱不清绑定（同上）</td></tr>
     * </table>
     *
     * <p><b>派发给谁</b>：{@code getApplicableFunctions(stack)} ——
     * <b>不用</b> {@code hasAnyFunctionFor} 的 copy 探针（它专为判定设计，
     * 且刻意不写进按 {@code Item} 缓存的表）。正因为两侧调用时物品都处于「活」状态，
     * 这里不需要探针。</p>
     *
     * <p>⚠️ <b>本方法不调 {@link #evaluate}</b> —— 判定留在调用方
     * （玩家点活按钮那条路需要 {@code Result} 给提示）。</p>
     *
     * @param stack 目标物品（服务端权威，直接改它）
     * @param level 世界（必填；三个入口天然都有 ⇒ 功能不必依赖 player 就能拿世界）
     * @param player 发起者；<b>可为 null</b>（内部产出 / 批量转化场景，见
     *               {@link LivingItemFunction#onActivated} 的降级约定）
     * @param via 发起途径
     * @param activate true = 活化；false = 取消活化
     * @return 状态是否真的被切换；<b>false = 下游否决</b>
     *         （{@link LivingItemFunction#onDeactivated} 返回了 false：
     *          框架已保持物品的活状态、未清任何数据。玩家点活按钮这条路今天不会
     *          走到 false —— 三个入口都带 player；它是为「批量转化」预留的。）
     */
    public static boolean apply(ItemStack stack, Level level, @Nullable Player player,
            Via via, boolean activate) {
        if (stack.isEmpty()) return false;

        if (activate) {
            LivingItemManager.setLiving(stack, true);
            for (LivingItemFunction function : LivingItemManager.getApplicableFunctions(stack)) {
                function.onActivated(stack, level, player, via);
            }
            return true;
        }

        for (LivingItemFunction function : LivingItemManager.getApplicableFunctions(stack)) {
            if (!function.onDeactivated(stack, level, player, via)) {
                return false;                    // 下游否决：不清任何数据
            }
        }
        LivingItemManager.setLiving(stack, false);
        return true;
    }
}
