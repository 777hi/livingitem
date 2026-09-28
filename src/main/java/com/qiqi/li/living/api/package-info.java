/**
 * 对外 API 契约入口 —— 第三方实现活物品的核心包。
 *
 * <h3>第三方从这里开始</h3>
 * <ul>
 *   <li>{@link com.qiqi.li.living.api.LivingItemFunction} —— 实现它 = 一个活物品功能
 *       （canApply 匹配 / tick 逻辑 / getOwnedComponentTypes 组件归属）</li>
 *   <li>{@link com.qiqi.li.living.api.LivingItemManager} —— 注册与查询（纯框架类，
 *       不含任何内容物类型）</li>
 *   <li>{@link com.qiqi.li.living.api.LivingItemActivation} /
 *       {@link com.qiqi.li.living.api.ActivationRuleConfig} —— 活化门禁（via 途径判定）</li>
 *   <li>{@link com.qiqi.li.living.api.HasContainerData} / {@link com.qiqi.li.living.api.HasDirection}
 *       —— 可选能力接口（容器级数据 / 朝向）</li>
 * </ul>
 *
 * <h3>不在本包的能力</h3>
 * <ul>
 *   <li>交互规则 —— 通过 {@code interaction_rules.json} 配置，见
 *       {@code docs/buffer/interaction-rule-design.md}（Java 面在
 *       {@code living/interaction/}，全部 internal）</li>
 *   <li>组件常量 —— 内容组件在 {@code living/transfer/LivingComponents}（注册站），
 *       各 Data 类自带 {@code of()/set()}</li>
 * </ul>
 *
 * <p>契约细节与不变量：{@code docs/system-design/api-contract.md}（违反即 bug）。</p>
 */
package com.qiqi.li.living.api;
