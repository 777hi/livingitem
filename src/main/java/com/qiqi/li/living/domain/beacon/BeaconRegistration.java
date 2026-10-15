package com.qiqi.li.living.domain.beacon;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.InteractionPredicates;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 活信标 域注册入口（A1 范式 —— 见 {@code RedstoneRegistration} 类注释）。
 *
 * <p>Phase 3：功能 + 遥测段 + 喂锭交互（handler + 谓词）。</p>
 *
 * <p>⚠️ 谓词必须在 {@code InteractionRuleConfig.load()} <b>之前</b>注册 ——
 * 规则装载时按 ID 查表，查不到就 WARN 并丢弃该条（沉默即缺陷）。本方法在主类的
 * 域注册块里调用，早于末尾的 load() ✓。</p>
 */
public final class BeaconRegistration {

    private BeaconRegistration() {
    }

    public static void register() {
        LivingItemManager.registerFunction(new BeaconFunction());
        // 运行时片段：激活等级 / 柱高 / 再生档的展示数据（档 2）
        RuntimeSegmentRegistry.register(BeaconSegment.INSTANCE);
        // 交互：光标拿矿石锭右键活信标 ⇒ 消耗 1 锭写入效果配置
        // 规则在 interaction_rules.json（id=beacon_feed，通配 trigger + predicate）
        InteractionRegistry.registerHandler("beacon_feed", new BeaconFeedHandler());
        InteractionPredicates.register("is_beacon_ore", BeaconFeedHandler::isOreIngot);
    }
}
