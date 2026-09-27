package com.qiqi.li.living.api;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

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
        REMOVED.clear();
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
        // 先应用删除：否则玩家删掉的内置规则会在下次 load 时被内置资源「复活」
        if (data.removed != null) {
            for (String sel : data.removed) {
                if (sel == null || sel.isBlank()) continue;
                RuleEntry r = findBySelector(sel);
                if (r != null) RULES.remove(r);
                REMOVED.add(sel);
            }
        }
        apply(data, "user");
    }

    // ── 指令侧：增删改与持久化 ─────────────────────────────────

    /** 被玩家显式移除的规则（选择器文本）—— 否则 reload 时会被内置资源「复活」。 */
    private static final Set<String> REMOVED = new LinkedHashSet<>();

    /**
     * 新增或更新一条规则（同一选择器覆盖）。
     *
     * @param selector  规范化选择器：`minecraft:chest` / `#minecraft:swords` / `@somemod`
     * @param activate  true = 设置 activate 动作；false = 设置 deactivate 动作
     * @param allow     true = allow；false = deny
     * @param via       作用途径；null / 空 = 取缺省（仅 player）
     * @return true = 新增；false = 覆盖了已有规则
     */
    public static boolean put(String selector, boolean activate, boolean allow, @Nullable List<Via> via) {
        if (!parseSelectorInto(selector, new RuleEntry())) {
            return false;                                  // 选择器不合法
        }
        Action action = allow ? Action.ALLOW : Action.DENY;
        RuleEntry r = findBySelector(selector);
        boolean isNew = r == null;
        if (isNew) {
            r = new RuleEntry();
            parseSelectorInto(selector, r);
        }
        if (activate) r.activate = action; else r.deactivate = action;
        if (via != null && !via.isEmpty()) r.viaSet = EnumSet.copyOf(via);
        if (isNew) RULES.add(r);
        REMOVED.remove(selector);                          // 重新加回来 ⇒ 取消先前的删除记录
        return isNew;
    }

    /**
     * 移除一条规则（记入 removed，防止 reload 后被内置资源复活）。
     *
     * @return 是否确有变化（删掉了已有规则，或新记录了一条删除）
     */
    public static boolean remove(String selector) {
        RuleEntry r = findBySelector(selector);
        boolean had = r != null;
        if (r != null) RULES.remove(r);
        boolean recorded = REMOVED.add(selector);
        return had || recorded;
    }

    /** 逐条描述当前生效规则（供 {@code /livingitem activation list}）。 */
    public static List<String> describeRules() {
        List<String> out = new ArrayList<>();
        for (RuleEntry r : RULES) {
            StringBuilder sb = new StringBuilder(r.selector);
            sb.append("  activate=").append(r.activate == null ? "-" : r.activate.name().toLowerCase());
            sb.append("  deactivate=").append(r.deactivate == null ? "-" : r.deactivate.name().toLowerCase());
            sb.append("  via=").append(r.viaSet == null ? "player" : r.viaSet.toString());
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * 把玩家产生的差异写回 {@code config/living_item/activation_rules.json}。
     *
     * <p><b>只写差异</b> —— 内置规则不回写成副本，这样将来内置资源更新时
     * 不会被玩家的旧快照锁死（照 {@code ContainerRuleConfig} 的增量语义）。</p>
     */
    public static void save() {
        if (configDir == null) return;
        ConfigData data = new ConfigData();
        data.version = CONFIG_VERSION;
        data.defaultAction = defaultAction.name().toLowerCase();
        data.options = new Options();
        data.options.denyUnclaimed = denyUnclaimed;
        data.rules = new ArrayList<>();
        for (RuleEntry r : RULES) {
            RawRule raw = new RawRule();
            if (r.itemId != null) raw.item = r.selector;
            else if (r.itemTag != null) raw.tag = r.selector;
            else raw.namespace = r.namespace;
            raw.activate = r.activate == null ? null : r.activate.name().toLowerCase();
            raw.deactivate = r.deactivate == null ? null : r.deactivate.name().toLowerCase();
            if (r.viaSet != null) {
                raw.via = new ArrayList<>();
                for (Via v : r.viaSet) raw.via.add(v.name().toLowerCase());
            }
            data.rules.add(raw);
        }
        data.removed = new ArrayList<>(REMOVED);
        try {
            Files.createDirectories(configDir);
            try (var writer = new OutputStreamWriter(
                    new FileOutputStream(configDir.resolve(CONFIG_FILE).toFile()),
                    StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LivingItemManager.LOGGER.error("Failed to save activation rule config", e);
        }
    }

    private static RuleEntry findBySelector(String selector) {
        for (RuleEntry r : RULES) {
            if (selector.equals(r.selector)) return r;
        }
        return null;
    }

    /** 选择器文本是否合法（指令入口在写入前先校验）。 */
    public static boolean isValidSelector(String selector) {
        if (selector == null || selector.isBlank()) return false;
        return parseSelectorInto(selector, new RuleEntry());
    }

    /** 把选择器文本解析进规则（指令入口用，不经过 JSON）。 */
    private static boolean parseSelectorInto(String selector, RuleEntry r) {
        if (selector.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(selector.substring(1));
            if (id == null) return false;
            r.itemTag = id;
        } else if (selector.startsWith("@")) {
            r.namespace = selector.substring(1);
        } else {
            ResourceLocation id = ResourceLocation.tryParse(selector);
            if (id == null) return false;
            r.itemId = id;
        }
        r.selector = selector;
        return true;
    }

    /**
     * 测试注入口：直接喂一段 JSON，语义等同「清空后加载」（包级可见，同 {@code ContainerRuleConfig} 的做法）。
     * <b>仅供测试使用</b> —— 生产代码请走 {@link #load()}。
     */
    static void loadFromString(String json) {
        RULES.clear();
        REMOVED.clear();
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
                r.selector = id.toString();
            } else if (raw.tag != null) {
                String t = raw.tag.startsWith("#") ? raw.tag.substring(1) : raw.tag;
                ResourceLocation id = ResourceLocation.tryParse(t);
                if (id == null) { warnBad(source, "tag", raw.tag); continue; }
                r.itemTag = id;
                r.selector = "#" + id;                    // 规范化为带 # 的形态
            } else {
                r.namespace = raw.namespace;
                r.selector = "@" + raw.namespace;         // 规范化为带 @ 的形态
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
        /** 被显式移除的规则（选择器文本）—— 防止被内置资源重新加载回来 */
        List<String> removed;
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
        /**
         * 规则的原始选择器文本（规范化形态）：`minecraft:chest` / `#minecraft:swords` / `@somemod`。
         * 供 {@code save()} 回写 JSON 与「同一目标去重」使用 —— 没有它就无法把已解析的规则还原成输入语法。
         */
        String selector;
    }
}
