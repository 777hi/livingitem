package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.ActivationRuleConfig.Action;
import com.qiqi.li.living.api.LivingItemActivation.Via;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 活化规则 JSON 的加载语义测试（D1 ②）。
 *
 * <p>依据 <code>docs/buffer/activation-rule-design.md</code> 与 open-plan.md §5 原则 4：
 * <b>JSON 的失败天生是静默的</b> —— 字段拼错 / ID 不存在全都表现为「什么都不发生」，
 * 所以加载语义必须钉死。覆盖：三层语义 / via 缺省 / activate-deactivate 独立 /
 * 先命中先赢 / 白名单模式 / 坏值跳过。
 */
@DisplayName("活化规则 JSON · 加载语义（D1 ②）")
class ActivationRuleConfigTest {

    private static ItemStack item(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    }

    @AfterEach
    void reset() {
        ActivationRuleConfig.loadFromString("{}");
    }

    @Test
    @DisplayName("① 无规则时走 default=allow")
    void noRulesMeansAllow() {
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:bedrock"), true, Via.PLAYER));
    }

    @Test
    @DisplayName("② item 精确匹配 deny")
    void denyByItem() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"item":"minecraft:bedrock","activate":"deny"}]}
            """);
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:bedrock"), true, Via.PLAYER));
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:stone"), true, Via.PLAYER));
    }

    @Test
    @DisplayName("③ ⭐ via 省略 = 仅 player —— 禁玩家点活但不能误禁任务奖励（Q-D1-5）")
    void viaDefaultsToPlayerOnly() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"item":"minecraft:bedrock","activate":"deny"}]}
            """);
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:bedrock"), true, Via.PLAYER));
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:bedrock"), true, Via.EXTERNAL),
            "省略 via 的 deny 规则绝不能波及任务奖励/命令给予 —— 那是配置者主动发放活物品的渠道");
    }

    @Test
    @DisplayName("④ namespace 通配整个 modid")
    void namespaceWildcard() {
        // ⚠️ 测试环境（FML unit test）只有原版 + 本模组的物品，
        //    而 BuiltInRegistries.ITEM.get(不存在的ID) 会返回 AIR —— 不能用虚构 ID 做正例。
        //    所以正例用 minecraft 命名空间（规则2命中），负例用不存在的 cheatymod（规则1不命中）。
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"namespace":"cheatymod","activate":"deny"},
                      {"namespace":"minecraft","activate":"deny"}]}
            """);
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:stone"), true, Via.PLAYER),
            "命中 namespace=minecraft 的规则");
    }

    @Test
    @DisplayName("④b namespace 不匹配的物品不受该规则影响")
    void namespaceNonMatch() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"namespace":"cheatymod","activate":"deny"}]}
            """);
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:stone"), true, Via.PLAYER),
            "stone 属于 minecraft 命名空间，不该被 cheatymod 的规则命中");
    }

    @Test
    @DisplayName("⑤ activate 与 deactivate 互相独立")
    void activateDeactivateIndependent() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"item":"minecraft:chest","activate":"deny","deactivate":"allow"}]}
            """);
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:chest"), true, Via.PLAYER));
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:chest"), false, Via.PLAYER));
    }

    @Test
    @DisplayName("⑥ 先命中先赢（数组顺序语义）")
    void firstMatchWins() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"item":"minecraft:diamond_sword","activate":"allow"},
                      {"namespace":"minecraft","activate":"deny"}]}
            """);
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:diamond_sword"), true, Via.PLAYER));
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:stone"), true, Via.PLAYER));
    }

    @Test
    @DisplayName("⑦ 白名单模式：default=deny + allow 放行")
    void whitelistMode() {
        // 用 item 精确匹配测白名单语义（tag 路径见下方 @Disabled 的说明）
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"deny",
             "rules":[{"item":"minecraft:diamond_sword","activate":"allow"}]}
            """);
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:diamond_sword"), true, Via.PLAYER));
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:stone"), true, Via.PLAYER));
    }

    /**
     * ⚠️ <b>@Disabled 原因（2026-09-27 实测）</b>：FML unit test 环境不加载 item tags
     * —— {@code stack.is(TagKey)} 恒为 false（本项目 38 个测试类此前从无 TagKey 先例）。
     * {@code ActivationRuleConfig.itemMatches} 的 tag 分支逻辑与 item 分支同构，
     * 但「测试环境 tag 为空」意味着这里测不出真实行为 —— 强行断言只能得到假绿或假红。
     * <b>tag 路径需要在 runClient 里游戏内验证</b>：
     * 配 {@code {"tag":"#minecraft:swords","activate":"deny"}} 后对钻石剑执行
     * {@code /livingitem activation test}。
     */
    @Test
    @org.junit.jupiter.api.Disabled("FML unit test 不加载 item tags —— 待游戏内验证，见方法注释")
    @DisplayName("⑨ tag 匹配（游戏内验证）")
    void tagMatch() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"tag":"#minecraft:swords","activate":"deny"}]}
            """);
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:diamond_sword"), true, Via.PLAYER));
    }

    @Test
    @DisplayName("⑧ 坏值跳过不崩：未知 via / 未知 action / 无选择器")
    void malformedSkipped() {
        ActivationRuleConfig.loadFromString("""
            {"version":1,"default":"allow",
             "rules":[{"item":"minecraft:bedrock","activate":"deny","via":["nonsense"]},
                      {"item":"minecraft:bedrock","activate":"explode"},
                      {"activate":"deny"},
                      {"item":"minecraft:bedrock","activate":"deny"}]}
            """);
        // 第 1 条因 via 解析失败回落为"仅 player"判定仍然有效
        assertEquals(Action.DENY,
            ActivationRuleConfig.evaluate(item("minecraft:bedrock"), true, Via.PLAYER));
        assertEquals(Action.ALLOW,
            ActivationRuleConfig.evaluate(item("minecraft:stone"), true, Via.PLAYER));
    }
}
