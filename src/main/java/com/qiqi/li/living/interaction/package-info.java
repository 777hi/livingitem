/**
 * GUI 交互注册表 —— <b>对第三方而言，本包的使用接口是 JSON 而非 Java</b>。
 *
 * <p>交互规则在 {@code interaction_rules.json}（内置 + 玩家差异）配置：
 * {@code target × trigger × button → actionId}，actionId 只能引用本模组已注册的
 * handler（玩家不能创造新行为）。指令：{@code /livingitem interaction reload|list}。</p>
 *
 * <p>⚠️ 本包属<b>内部实现</b>（不在对外包清单内，见 api-contract.md）——
 * handler 是模组内容物，注册发生在 commonSetup。</p>
 */
package com.qiqi.li.living.interaction;
