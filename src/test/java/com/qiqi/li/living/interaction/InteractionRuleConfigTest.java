package com.qiqi.li.living.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 交互规则 JSON 加载语义守卫（D2）—— 三层来源 / 玩家覆盖与删除 / 坏值跳过 / 未知字段不崩。
 *
 * <p>口径与 {@code ActivationRuleConfigTest} 一致；tag 匹配路径不在本测试
 * （单测环境不加载 vanilla tags，同 activation 先例 @Disabled —— tag 只断言解析层）。</p>
 *
 * <p>⚠️ 本类<b>自带 handler / 谓词注册</b>（@BeforeEach 里 mock）而不依赖 commonSetup：
 * 测试类执行顺序不定，{@code InteractionRegistryTest} 的 {@code clearForTest} 会清掉
 * 全局静态 —— 依赖别的测试先跑过 commonSetup 的状态 = 顺序耦合的定时炸弹。</p>
 */
class InteractionRuleConfigTest {

    private static final int FIXTURE_BUNDLED_COUNT = 15;
    private static final String[] ACTIONS = {
        "ignite", "ignite_carried", "button_press", "lever_toggle",
        "repeater_cycle", "comparator_toggle", "till_to_farmland", "plant_crop", "bonemeal", "axe_strip" };

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        InteractionRegistry.clearForTest();
        InteractionPredicates.clearForTest();
        for (String action : ACTIONS) {
            InteractionRegistry.registerHandler(action, (player, slot) -> { });
        }
        InteractionPredicates.register("can_till", (t, g) -> true);
        InteractionPredicates.register("can_plant", (t, g) -> true);
        InteractionPredicates.register("can_strip_with", (t, g) -> true);
        InteractionRuleConfig.init(tempDir);
    }

    @AfterEach
    void tearDown() {
        InteractionRegistry.clearForTest();
        InteractionPredicates.clearForTest();
        InteractionRuleConfig.init(null);
    }

    private void writeUserFile(String json) throws Exception {
        Files.writeString(tempDir.resolve("interaction_rules.json"), json);
    }

    // ── 1. 内置规则有效性（Q-D2-1 的保险：把编译期检查平移成测试期检查） ──

    @Test
    @DisplayName("内置 JSON 全部装载成功：每条的 action 有 handler、物品真实存在")
    void bundledRulesLoadAndValidate() {
        InteractionRuleConfig.load();

        int count = InteractionRuleConfig.ruleCount();
        assertEquals(FIXTURE_BUNDLED_COUNT, count, "本 fixture 注册的内置规则应全部有效");
        assertEquals(count, InteractionRuleConfig.bundledCount(),
            "无玩家文件时全部规则都应是内置的");

        for (InteractionEntry e : InteractionRegistry.getEntries()) {
            assertNotNull(InteractionRegistry.getHandler(e.actionId()),
                "每条规则的 action 必须有 handler: " + e.actionId());
            if (e.targetItem() != null) {
                assertNotEquals(Items.AIR, e.targetItem(), "target 物品不得解析成 AIR（拼错 ID 的迹象）");
            }
            if (e.triggerItem() != null) {
                assertNotEquals(Items.AIR, e.triggerItem(), "trigger 物品不得解析成 AIR");
            }
        }
    }

    @Test
    @DisplayName("buttons_press 用 tag 表达：#minecraft:buttons 一条覆盖 13 种按钮")
    void bundledButtonRuleUsesVanillaTag() {
        InteractionRuleConfig.load();
        // tag 匹配结果不断言（单测环境无 vanilla tags），只断言解析层正确创建
        boolean found = InteractionRegistry.getEntries().stream()
            .anyMatch(e -> e.targetTag() != null
                && e.targetTag().location().equals(ResourceLocation.withDefaultNamespace("buttons"))
                && "button_press".equals(e.actionId()));
        assertTrue(found, "应有 target=#minecraft:buttons 的 button_press 规则");
    }

    @Test
    @DisplayName("去皮内置规则：logs tag 与竹块物品，通配光标 + can_strip_with，仅右键按下")
    void bundledAxeStripRulesUseTagAndBambooItem() {
        InteractionRuleConfig.load();

        List<InteractionEntry> strips = InteractionRegistry.getEntries().stream()
            .filter(e -> "axe_strip".equals(e.actionId())).toList();
        assertEquals(2, strips.size(), "应加载 logs 与 bamboo 两条去皮规则");
        InteractionEntry logs = strips.stream()
            .filter(e -> ItemTags.LOGS.equals(e.targetTag())).findFirst().orElse(null);
        assertNotNull(logs, "原木去皮应声明 target=#minecraft:logs，不展开未加载的 tags");
        assertNull(logs.targetItem());
        InteractionEntry bamboo = strips.stream()
            .filter(e -> e.targetItem() == Items.BAMBOO_BLOCK).findFirst().orElse(null);
        assertNotNull(bamboo, "竹块去皮应声明具体物品 minecraft:bamboo_block");
        assertNull(bamboo.targetTag());
        for (InteractionEntry entry : strips) {
            assertNull(entry.triggerItem(), "省略 trigger，不限于某个斧物品");
            assertNull(entry.triggerTag(), "能力工具不依赖 trigger tag");
            assertEquals(1, entry.button());
            assertFalse(entry.onRelease());
            assertSame(InteractionPredicates.get("can_strip_with"), entry.triggerFilter(),
                "通配规则必须绑定 can_strip_with，不能吞掉普通光标操作");
        }
        List<String> descriptions = InteractionRuleConfig.describeRules();
        assertTrue(descriptions.stream().anyMatch(s -> s.startsWith("[axe_strip_logs](bundled)")));
        assertTrue(descriptions.stream().anyMatch(s -> s.startsWith("[axe_strip_bamboo](bundled)")));
    }

    // ── 2. 玩家差异：覆盖 / 删除 / 追加 ──

    @Test
    @DisplayName("玩家按 id 覆盖内置规则（bundled 计数不变）")
    void userOverridesBundledById() throws Exception {
        writeUserFile("""
            { "version": 1, "rules": [
              { "id": "farmland_bonemeal", "target": "minecraft:farmland",
                "trigger": "minecraft:bone_meal", "button": "left", "action": "bonemeal" }
            ] }""");
        InteractionRuleConfig.load();

        // 覆盖后：总数不变（同 id 替换），bundled 计数仍含该 id
        assertEquals(FIXTURE_BUNDLED_COUNT, InteractionRuleConfig.ruleCount());
        assertEquals(FIXTURE_BUNDLED_COUNT, InteractionRuleConfig.bundledCount());
        // 生效侧：bonemeal 条目的 button 变成 left（0）
        boolean overridden = InteractionRegistry.getEntries().stream()
            .anyMatch(e -> "bonemeal".equals(e.actionId()) && e.button() == 0);
        assertTrue(overridden, "内置 bonemeal 应被玩家的 left 键版本覆盖");
    }

    @Test
    @DisplayName("玩家 removed 隐藏内置规则（防 reload 复活语义）")
    void userRemovedHidesBundledRule() throws Exception {
        writeUserFile("{ \"version\": 1, \"removed\": [\"tnt_ignite\"] }");
        InteractionRuleConfig.load();

        assertEquals(FIXTURE_BUNDLED_COUNT - 1, InteractionRuleConfig.ruleCount());
        boolean gone = InteractionRegistry.getEntries().stream()
            .noneMatch(e -> e.targetItem() == Items.TNT && "ignite".equals(e.actionId()));
        assertTrue(gone, "tnt_ignite 应被移除");
    }

    @Test
    @DisplayName("玩家 removed 可删除两条去皮规则，重复 reload 不复活且不影响其它规则")
    void userRemovedAxeStripRulesStayRemovedOnReload() throws Exception {
        InteractionRuleConfig.load();
        List<InteractionEntry> remaining = InteractionRegistry.getEntries().stream()
            .filter(e -> !"axe_strip".equals(e.actionId())).toList();
        assertEquals(FIXTURE_BUNDLED_COUNT - 2, remaining.size());
        writeUserFile("""
            { "version": 1, "removed": ["axe_strip_logs", "axe_strip_bamboo"] }
            """);

        for (int reload = 0; reload < 2; reload++) {
            InteractionRuleConfig.load();
            assertEquals(FIXTURE_BUNDLED_COUNT - 2, InteractionRuleConfig.ruleCount());
            assertEquals(remaining, InteractionRegistry.getEntries(),
                "移除去皮后仅保留其它规则，reload 不应使去皮复活");
            assertTrue(InteractionRuleConfig.describeRules().stream()
                .noneMatch(s -> s.startsWith("[axe_strip_logs]") || s.startsWith("[axe_strip_bamboo]")));
        }
    }

    @Test
    @DisplayName("玩家追加新 id 的规则")
    void userAppendsNewRule() throws Exception {
        writeUserFile("""
            { "version": 1, "rules": [
              { "id": "mypack_cool_thing", "target": "minecraft:tnt",
                "trigger": "minecraft:blaze_powder", "button": "right", "action": "ignite" }
            ] }""");
        InteractionRuleConfig.load();

        assertEquals(FIXTURE_BUNDLED_COUNT + 1, InteractionRuleConfig.ruleCount());
        assertEquals(FIXTURE_BUNDLED_COUNT, InteractionRuleConfig.bundledCount());
    }

    // ── 3. 坏值：WARN + 跳过，绝不崩 ──

    @Test
    @DisplayName("未知物品 / 未知 action / 未知谓词 / 坏 button / 缺 id —— 逐条跳过，好规则照常生效")
    void invalidRulesAreSkippedNotFatal() throws Exception {
        writeUserFile("""
            { "version": 1, "rules": [
              { "id": "bad_item",     "target": "minecraft:not_an_item", "button": "right", "action": "ignite" },
              { "id": "bad_action",   "target": "minecraft:tnt", "button": "right", "action": "no_such_action" },
              { "id": "bad_pred",     "target": "minecraft:tnt", "button": "right", "action": "ignite", "predicate": "no_such_pred" },
              { "id": "bad_button",   "target": "minecraft:tnt", "button": "middle_left", "action": "ignite" },
              { "id": "",             "target": "minecraft:tnt", "button": "right", "action": "ignite" },
              { "id": "good_one",     "target": "minecraft:tnt", "trigger": "minecraft:blaze_powder", "button": "right", "action": "ignite" }
            ] }""");
        InteractionRuleConfig.load();

        // 只有 good_one 被接受：fixture 内置规则 + 1
        assertEquals(FIXTURE_BUNDLED_COUNT + 1, InteractionRuleConfig.ruleCount());
    }

    @Test
    @DisplayName("未知字段（拼写错误）被忽略，规则本体照常生效")
    void unknownFieldIsIgnoredButRuleSurvives() throws Exception {
        writeUserFile("""
            { "version": 1, "rules": [
              { "id": "typo_rule", "target": "minecraft:tnt", "buttton": "right",
                "button": "right", "action": "ignite" }
            ] }""");
        InteractionRuleConfig.load();

        assertEquals(FIXTURE_BUNDLED_COUNT + 1, InteractionRuleConfig.ruleCount(), "未知字段不应导致整条规则被丢弃");
    }

    @Test
    @DisplayName("version 不识别 → 跳过整个文件（玩家坏文件不会炸掉内置规则）")
    void wrongVersionSkipsWholeFile() throws Exception {
        writeUserFile("{ \"version\": 99, \"rules\": [ { \"id\": \"x\", \"target\": \"minecraft:tnt\", \"button\": \"right\", \"action\": \"ignite\" } ] }");
        InteractionRuleConfig.load();

        assertEquals(FIXTURE_BUNDLED_COUNT, InteractionRuleConfig.ruleCount());
        assertFalse(InteractionRuleConfig.describeRules().stream().anyMatch(s -> s.contains("x]")));
    }

    @Test
    @DisplayName("reload 幂等：连调两次结果一致")
    void loadIsIdempotent() {
        InteractionRuleConfig.load();
        int first = InteractionRuleConfig.ruleCount();
        List<String> firstDesc = InteractionRuleConfig.describeRules();
        InteractionRuleConfig.load();
        assertEquals(first, InteractionRuleConfig.ruleCount());
        assertEquals(firstDesc, InteractionRuleConfig.describeRules());
    }
}
