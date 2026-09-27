package com.qiqi.li.living.api;

import net.minecraft.world.item.ItemStack;

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
 *   <tr><td>{@link Via#EXTERNAL}</td><td>任务奖励 / 命令给予 / 掉落</td><td>❌ 否 —— <b>这正是整合包作者的发放渠道</b></td></tr>
 *   <tr><td>{@link Via#INTERNAL}</td><td>模组内部产出（活耕地、活地图）</td><td>❌ 否 —— 禁了功能就坏了</td></tr>
 * </table>
 *
 * <h3>第一版的规则</h3>
 * <p>目前只有一条<b>默认行为</b>（不可配）：</p>
 * <ul>
 *   <li><b>拒绝「无功能认领」的活化</b> —— 活化一个没有任何 {@link LivingItemFunction}
 *       认领的物品，只会让它进入<b>活物品隔离</b>（不传输 / 不熔炼 / 不作燃料）而<b>零收益</b>。
 *       这正是 <b>P1-3</b> 的本体：玩家误点按钮把物品变活，第三方会以为是自己模组的 bug。</li>
 * </ul>
 * <p>JSON 规则（黑名单 / 白名单 / 按途径）将在后续补入，届时本类的调用方式不变。</p>
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
        // 取消活化总是允许：把物品恢复成普通物品不会带来副作用
        // （活箱子的掉落、末影箱的解绑由调用方处理）
        if (!activate) {
            return Result.ALLOW;
        }
        // 目前只对「玩家手动活化」做无功能认领的拦截：
        // 外部途径（任务奖励）由整合包作者明确指定物品，他应当知道自己在发什么；
        // 内部途径是模组自己产出的，必然有对应功能。
        if (!LivingItemManager.hasAnyFunctionFor(stack)) {
            return Result.DENY_UNCLAIMED;
        }
        return Result.ALLOW;
    }

    /** 便捷方法：只要{@link Result#ALLOW}才返回 true。 */
    public static boolean isAllowed(ItemStack stack, boolean activate, Via via) {
        return evaluate(stack, activate, via) == Result.ALLOW;
    }
}
