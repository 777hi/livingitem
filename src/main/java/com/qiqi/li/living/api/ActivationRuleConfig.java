package com.qiqi.li.living.api;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.qiqi.li.living.api.LivingItemActivation.Via;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 活化规则配置 —— 允许配置者（服务器主 / 整合包作者）<b>不写一行 Java</b> 控制哪些物品可被活化。
 *
 * <h3>三层来源（优先级从高到低）</h3>
 * <ol>
 *   <li><b>玩家规则</b> —— {@code config/living_item/activation_rules.json}
 *       （本模组目前只做这一层；内置资源随 jar 提供依据默认值）</li>
 *   <li><b>内置规则</b> —— {@code assets/living_item/activation_rules.json}</li>
 * </ol>
 *
 * <h3>JSON 格式</h3>
 * <pre>{@code
 * {
 *   "version": 1,
 *   "default": "allow",
 *   "rules": [
 *     { "item": "minecraft:bedrock",  "activate": "deny", "via": ["player"] },
 *     { "tag": "#minecraft:swords",   "activate": "deny" },
 *     { "namespace": "cheatymod",     "activate": "deny" },
 *     { "item": "minecraft:chest",    "deactivate": "deny" }
 *   ],
 *   "options": { "denyUnclaimed": true }
 * }
 * }</pre>
 *
 * <p><b>匹配</b>：按 {@code rules} 数组顺序，<b>先命中先赢</b>；都不命中则取 {@code default}。</p>
 *
 * <p>⚠️ <b>{@code via} 省略时 = 仅 {@code player}</b>（不是全部途径）——
 * 这是刻意的：本功能的目的就是「只封玩家自己动手活化」，若缺省是全部途径，
 * 配置者一行 deny 就会误禁掉<b>自己主动发放活物品的渠道</b>。见设计稿 Q-D1-5。</p>
 *
 * <p>设计稿见 {@code docs/buffer/activation-rule-design.md}；
 * 模式照抄 {@code ContainerRuleConfig}（幂等 load / 显式 UTF-8 / malformed 跳过不崩）。</p>
 */
public final class ActivationRuleConfig {

    public enum Action { ALLOW, DENY }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "activation_rules.json";
    private static final String BUNDLED_RESOURCE = "/assets/living_item/" + CONFIG_FILE;
    private static final int CONFIG_VERSION = 1;

    /** 有效动作（低层：内置，高层：玩家覆盖 / 新增） */
    private static final List<RuleEntry> RULES = new ArrayList<>();
    private static Action defaultAction = Action.ALLOW;
    /**
     * ⚠️ 默认 {@code false}（2026-09-27 用户定调）：<b>默认行为不可动</b> ——
     * 原本任何物品都能被活化，这个拦截只能由配置者在 JSON 里显式开启，
     * 模组自己不得替用户决定拒绝。
     */
    private static boolean denyUnclaimed = false;

    private static Path configDir = null;

    private ActivationRuleConfig() {}

    public static void init(Path configDirPath) {
        configDir = configDirPath.resolve("living_item");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LivingItemManager.LOGGER.error("Failed to create activation rule config directory", e);
        }
    }

    /**
     * 加载规则。<b>幂等</b>：先清空上一次加载的状态，再从资源/磁盘重建
     * ⇒ {@code /livingitem activation reload} 能真正反映磁盘当前内容。
     */
    public static void load() {
        RULES.clear();
        defaultAction = Action.ALLOW;
        denyUnclaimed = false;

        loadFromResource(BUNDLED_RESOURCE, "bundled");

        Path userFile = configDir == null ? null : configDir.resolve(CONFIG_FILE);
        if (userFile != null && Files.exists(userFile)) {
            try (Reader reader = new InputStreamReader(
                    new FileInputStream(userFile.toFile()), StandardCharsets.UTF_8)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) applyUserData(data);
            } catch (Exception e) {
                LivingItemManager.LOGGER.error("Failed to load user activation rule config", e);
            }
        }
        LivingItemManager.LOGGER.info("Activation rules ready: {} effective rule(s), default={}",
            RULES.size(), defaultAction);
    }

    /** {@code options.denyUnclaimed}：拒绝「无功能认领」的活化（见 LivingItemActivation）。 */
    public static boolean isDenyUnclaimed() {
        return denyUnclaimed;
    }

    /** 当前生效规则条数（{@code /livingitem activation list} 用） */
    public static int ruleCount() {
        return RULES.size();
    }

    /** 无规则命中时的默认动作 */
    public static Action getDefaultAction() {
        return defaultAction;
    }

    /**
     * 判定一次活化/取消活化。
     *
     * @param stack    目标物品
     * @param activate true = 活化
     * @param via      发起途径
     * @return 允许还是拒绝
     */
    public static Action evaluate(ItemStack stack, boolean activate, Via via) {
        // ⭐ 特异性优先：所有命中的规则里，【更精确】的那条胜出
        //   item(0) > tag(1) > namespace(2)；同等精确时保持「先到先得」（不更新 best）。
        //   为什么不是纯顺序：纯顺序下，指令【追加】的 allow 永远排在先前的 deny 之后，
        //   ⇒ 「禁了 #swords、再单独放行某把剑」这种组合根本表达不出来。
        RuleEntry best = null;
        for (RuleEntry r : RULES) {
            if (!viaMatches(r, via)) continue;
            if (!itemMatches(r, stack)) continue;
            if (best == null || specificity(r) < specificity(best)) {
                best = r;
            }
        }
        if (best == null) return defaultAction;
        Action a = activate ? best.activate : best.deactivate;
        return a != null ? a : defaultAction;
    }

    /** 特异性：数值越小越精确 —— item(0) > tag(1) > namespace(2) */
    private static int specificity(RuleEntry r) {
        if (r.itemId != null) return 0;
        if (r.itemTag != null) return 1;
        return 2;                                   // namespace
    }

    /** {@code via} 省略 ⇒ 仅 player（见类注释）。 */
    private static boolean viaMatches(RuleEntry r, Via via) {
        Set<Via> set = r.viaSet != null ? r.viaSet : EnumSet.of(Via.PLAYER);
        return set.contains(via);
    }

    private static boolean itemMatches(RuleEntry r, ItemStack stack) {
        if (r.itemId != null) {
            Item item = BuiltInRegistries.ITEM.get(r.itemId);
            return !stack.isEmpty() && stack.is(item);
        }
        if (r.itemTag != null) {
            return stack.is(TagKey.create(Registries.ITEM, r.itemTag));
        }
        if (r.namespace != null) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && r.namespace.equals(id.getNamespace());
        }
        return false;
    }

    // ── 加载 ───────────────────────────────────────────────────

    private static void loadFromResource(String resourcePath, String source) {
        try (var in = ActivationRuleConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LivingItemManager.LOGGER.warn("Bundled activation rule resource not found: {}", resourcePath);
                return;
            }
            try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                apply(GSON.fromJson(reader, ConfigData.class), source);
            }
        } catch (Exception e) {
            LivingItemManager.LOGGER.error("Failed to load activation rules from {}", source, e);
        }
    }

    private static void applyUserData(ConfigData data) {
        apply(data, "user");
    }

    /**
     * 测试注入口：直接喂一段 JSON，语义等同「清空后加载」（包级可见，同 {@code ContainerRuleConfig} 的做法）。
     * <b>仅供测试使用</b> —— 生产代码请走 {@link #load()}。
     */
    static void loadFromString(String json) {
        RULES.clear();
        defaultAction = Action.ALLOW;
        denyUnclaimed = false;
        apply(GSON.fromJson(json, ConfigData.class), "test");
    }

    private static void apply(ConfigData data, String source) {
        if (data == null) return;
        if (data.options != null) {
            denyUnclaimed = data.options.denyUnclaimed;
        }
        if (data.defaultAction != null) {
            Action a = parseAction(data.defaultAction, source, "default");
            if (a != null) defaultAction = a;
        }
        if (data.rules == null) return;

        for (RawRule raw : data.rules) {
            RuleEntry r = new RuleEntry();
            // 选择器：三者最多一个，多于一个只认第一个并告警
            int selectors = (raw.item != null ? 1 : 0) + (raw.tag != null ? 1 : 0)
                + (raw.namespace != null ? 1 : 0);
            if (selectors == 0) {
                LivingItemManager.LOGGER.warn("[{}] Ignoring activation rule with no selector "
                    + "(need one of: item / tag / namespace)", source);
                continue;
            }
            if (selectors > 1) {
                LivingItemManager.LOGGER.warn("[{}] Activation rule declares {} selectors — "
                    + "using the first one only.", source, selectors);
            }
            if (raw.item != null) {
                ResourceLocation id = ResourceLocation.tryParse(raw.item);
                if (id == null) { warnBad(source, "item", raw.item); continue; }
                r.itemId = id;
            } else if (raw.tag != null) {
                String t = raw.tag.startsWith("#") ? raw.tag.substring(1) : raw.tag;
                ResourceLocation id = ResourceLocation.tryParse(t);
                if (id == null) { warnBad(source, "tag", raw.tag); continue; }
                r.itemTag = id;
            } else {
                r.namespace = raw.namespace;
            }
            r.activate = parseAction(raw.activate, source, "activate");
            r.deactivate = parseAction(raw.deactivate, source, "deactivate");
            if (raw.via != null) {
                r.viaSet = EnumSet.noneOf(Via.class);
                for (String v : raw.via) {
                    try {
                        r.viaSet.add(Via.valueOf(v.trim().toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        LivingItemManager.LOGGER.warn("[{}] Ignoring unknown via '{}' "
                            + "(expected: player / external / internal)", source, v);
                    }
                }
                if (r.viaSet.isEmpty()) r.viaSet = null;
            }
            RULES.add(r);
        }
    }

    private static void warnBad(String source, String field, String value) {
        LivingItemManager.LOGGER.warn("[{}] Ignoring malformed {} in activation rule: {}", source, field, value);
    }

    private static Action parseAction(String s, String source, String field) {
        if (s == null || s.isBlank()) return null;
        switch (s.trim().toLowerCase()) {
            case "allow": return Action.ALLOW;
            case "deny": return Action.DENY;
            default:
                LivingItemManager.LOGGER.warn("[{}] Ignoring unknown {} '{}' (expected: allow / deny)",
                    source, field, s);
                return null;
        }
    }

    // ── JSON 结构 ──────────────────────────────────────────────

    private static final class ConfigData {
        int version = CONFIG_VERSION;
        /** JSON 字段名是 {@code default}（Java 里 default 是关键字，故加 SerializedName） */
        @SerializedName("default")
        String defaultAction;
        List<RawRule> rules = new ArrayList<>();
        Options options;
    }

    /** JSON 原始条目（字符串形态，未经校验）—— 与已解析的 {@link RuleEntry} 分开 */
    private static final class RawRule {
        String item;
        String tag;
        String namespace;
        String activate;
        String deactivate;
        List<String> via;
    }

    private static final class Options {
        boolean denyUnclaimed = false;
    }

    /** 已解析并校验过的规则 */
    private static final class RuleEntry {
        ResourceLocation itemId;
        ResourceLocation itemTag;
        String namespace;
        Action activate;
        Action deactivate;
        Set<Via> viaSet;                       // null = 取默认（仅 player）
    }
}
