package com.qiqi.li.living.interaction;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 交互规则配置 —— 规则（{@link InteractionEntry}）的 JSON 加载器（D2 · Q-D2-1 全迁）。
 *
 * <p>三层来源，与 {@code ContainerRuleConfig} / {@code ActivationRuleConfig} 同构
 * （照抄不发明第二套）：</p>
 *
 * <ol>
 *   <li><b>内置规则</b> —— 随 jar 打包的 {@code assets/living_item/interaction_rules.json}，
 *       每条带稳定 {@code id}（玩家删除/覆盖的锚点）</li>
 *   <li><b>玩家差异</b> —— {@code config/living_item/interaction_rules.json}：
 *       {@code rules[]} 追加 / 按 id 覆盖内置，{@code removed[]} 删除内置（防 reload 复活）</li>
 * </ol>
 *
 * <p><b>分层边界</b>：规则的 {@code id} 是<b>配置层概念</b>（差异的 key），只活在
 * 本类的 {@code RULES} 映射里 —— {@link InteractionEntry} 是运行时匹配对象，
 * 不需要知道自己叫什么。装载流程：先在本类把三层合并成最终表，
 * 再一次性灌入 {@code InteractionRegistry}（reload 幂等由此保证）。</p>
 *
 * <p><b>增量语义</b>：玩家文件只存差异；玩家没写过文件时它不存在（首次启动不生成），
 * 直接手写即可，改完 {@code /livingitem interaction reload} 生效 —— 本类没有
 * {@code save()}，因为没有指令改规则（设计稿 §3：四元组用指令拼参数比改 JSON 繁）。</p>
 *
 * <p><b>失败语义（沉默即缺陷）</b>：版本不识别 / 缺字段 / 坏值 / 未知物品 ID /
 * 未知 actionId / 未知谓词 ID / <b>未知字段</b>（如 {@code "buttton"} 拼错 ——
 * Gson 默认静默忽略，这里手动检测，这层 {@code ContainerRuleConfig} 都没有）
 * ⇒ 一律 WARN 并跳过该条，绝不崩游戏。</p>
 *
 * <p><b>加载时机</b>：commonSetup 里所有域 {@code XxxRegistration.register()} 之后 ——
 * handler 先注册，规则加载时才能校验 {@code action} 引用有效。</p>
 */
public final class InteractionRuleConfig {

    private static final String CONFIG_FILE = "interaction_rules.json";
    private static final String BUNDLED_RESOURCE = "/assets/living_item/" + CONFIG_FILE;
    private static final int CONFIG_VERSION = 1;

    /** 规则对象允许的全部字段 —— 多出的都是拼写错误，WARN（沉默即缺陷）。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
        "id", "target", "trigger", "button", "action", "onRelease", "predicate");

    /** 合并后的最终规则表（id → 条目，顺序 = 注册顺序）。 */
    private static final Map<String, InteractionEntry> RULES = new LinkedHashMap<>();
    /** 内置规则的 id 集合 —— {@code list} 指令区分来源用（被玩家覆盖后仍算内置 id）。 */
    private static final Set<String> BUNDLED_IDS = new HashSet<>();

    private static Path configDir;

    private InteractionRuleConfig() {}

    public static void init(Path dir) {
        configDir = dir;
    }

    /**
     * 重载全部规则（幂等）：内置 + 玩家差异合并后整体替换 Registry 的条目。
     * handler 不动（它们是代码，在 commonSetup 由各域注册）。
     */
    public static void load() {
        Map<String, InteractionEntry> merged = new LinkedHashMap<>();
        Set<String> bundled = new HashSet<>();

        parseResource(BUNDLED_RESOURCE, "bundled", merged, bundled);
        parseUserFile(merged, bundled);

        RULES.clear();
        RULES.putAll(merged);
        BUNDLED_IDS.clear();
        BUNDLED_IDS.addAll(bundled);

        InteractionRegistry.clearEntries();
        RULES.values().forEach(InteractionRegistry::register);

        LivingItemManager.LOGGER.info("Interaction rules ready: {} effective ({} bundled + {} user)",
            RULES.size(), BUNDLED_IDS.size(), RULES.size() - BUNDLED_IDS.size());
    }

    /** 当前生效规则条数（{@code /livingitem interaction list} 用）。 */
    public static int ruleCount() {
        return RULES.size();
    }

    /** 内置规则条数（含被玩家覆盖的）。 */
    public static int bundledCount() {
        return BUNDLED_IDS.size();
    }

    /** 逐条描述当前生效规则（list 指令用），标注来源。 */
    public static List<String> describeRules() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, InteractionEntry> e : RULES.entrySet()) {
            out.add("[" + e.getKey() + "]"
                + (BUNDLED_IDS.contains(e.getKey()) ? "(bundled)" : "(user)")
                + " " + describe(e.getValue()));
        }
        return out;
    }

    private static String describe(InteractionEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("target=").append(describeItemOrTag(e.targetItem(), e.targetTag()));
        if (e.triggerItem() != null || e.triggerTag() != null) {
            sb.append(" trigger=").append(describeItemOrTag(e.triggerItem(), e.triggerTag()));
        } else {
            sb.append(" trigger=*");
        }
        sb.append(" button=").append(switch (e.button()) { case 0 -> "left"; case 1 -> "right"; default -> "middle"; });
        sb.append(" action=").append(e.actionId());
        if (e.onRelease()) sb.append(" onRelease");
        return sb.toString();
    }

    private static String describeItemOrTag(@Nullable Item item, @Nullable TagKey<Item> tag) {
        return item != null ? BuiltInRegistries.ITEM.getKey(item).toString() : "#" + tag.location();
    }

    // ── 解析 ──────────────────────────────────────────────────

    private static void parseResource(String resourcePath, String source,
            Map<String, InteractionEntry> merged, Set<String> bundled) {
        try (var in = InteractionRuleConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LivingItemManager.LOGGER.warn("Bundled interaction rule resource not found: {}", resourcePath);
                return;
            }
            parseRoot(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)), source, merged, bundled);
        } catch (Exception e) {
            LivingItemManager.LOGGER.error("Failed to load interaction rules from {}", source, e);
        }
    }

    private static void parseUserFile(Map<String, InteractionEntry> merged, Set<String> bundled) {
        if (configDir == null) return;
        Path file = configDir.resolve(CONFIG_FILE);
        if (!Files.exists(file)) return;           // 没写过 = 没有差异，正常
        try (Reader reader = new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {
            parseRoot(JsonParser.parseReader(reader), "user", merged, bundled);
        } catch (Exception e) {
            LivingItemManager.LOGGER.error("Failed to load user interaction rules from {}", file, e);
        }
    }

    /** 根对象：版本检查 → removed 先应用 → rules 逐条解析。 */
    private static void parseRoot(JsonElement rootEl, String source,
            Map<String, InteractionEntry> merged, Set<String> bundled) {
        if (!rootEl.isJsonObject()) {
            LivingItemManager.LOGGER.warn("[interaction-rules/{}] 根节点不是对象，跳过整个文件", source);
            return;
        }
        JsonObject root = rootEl.getAsJsonObject();
        JsonElement version = root.get("version");
        if (version == null || version.getAsInt() != CONFIG_VERSION) {
            LivingItemManager.LOGGER.warn(
                "[interaction-rules/{}] version 缺失或不识别（期望 {}），跳过整个文件", source, CONFIG_VERSION);
            return;
        }

        if (root.has("removed") && root.get("removed").isJsonArray()) {
            for (JsonElement e : root.get("removed").getAsJsonArray()) {
                String id = e.isJsonPrimitive() ? e.getAsString() : null;
                if (id != null && merged.remove(id) != null) {
                    LivingItemManager.LOGGER.info("[interaction-rules/{}] removed: {}", source, id);
                }
            }
        }

        if (root.has("rules") && root.get("rules").isJsonArray()) {
            for (JsonElement e : root.get("rules").getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    LivingItemManager.LOGGER.warn("[interaction-rules/{}] rules 里出现非对象元素，跳过", source);
                    continue;
                }
                parseRule(e.getAsJsonObject(), source, merged, bundled);
            }
        }
    }

    /**
     * 一条规则 → {@link InteractionEntry} 并放入 {@code merged}（同 id 天然覆盖）。
     * 任何一步不合法：WARN + 直接返回（跳过该条），绝不抛出。
     */
    private static void parseRule(JsonObject o, String source,
            Map<String, InteractionEntry> merged, Set<String> bundled) {
        for (String key : o.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                LivingItemManager.LOGGER.warn(
                    "[interaction-rules/{}] 规则含未知字段 \"{}\"（常见原因：拼写错误，本字段被忽略）——合法字段: {}",
                    source, key, KNOWN_KEYS);
            }
        }

        String id = string(o, "id");
        if (id == null || id.isBlank()) {
            LivingItemManager.LOGGER.warn("[interaction-rules/{}] 规则缺 \"id\"，跳过: {}", source, o);
            return;
        }

        ItemOrTag target = parseItemOrTag(string(o, "target"), "target", id, source);
        if (target == null) return;
        ItemOrTag trigger = parseItemOrTag(string(o, "trigger"), "trigger", id, source);
        if (trigger == null) return;                       // 显式写了但坏 ⇒ 丢弃；未写 = 通配是另一回事

        Integer button = parseButton(string(o, "button"), id, source);
        if (button == null) return;

        String action = string(o, "action");
        if (action == null || action.isBlank()) {
            LivingItemManager.LOGGER.warn("[interaction-rules/{}/{}] 缺 \"action\"，跳过", source, id);
            return;
        }
        if (InteractionRegistry.getHandler(action) == null) {
            LivingItemManager.LOGGER.warn(
                "[interaction-rules/{}/{}] action \"{}\" 没有已注册的 handler —— 规则只能引用模组已有动作，跳过",
                source, id, action);
            return;
        }

        BiPredicate<ItemStack, ItemStack> predicate = null;
        String predId = string(o, "predicate");
        if (predId != null) {
            predicate = InteractionPredicates.get(predId);
            if (predicate == null) {
                LivingItemManager.LOGGER.warn(
                    "[interaction-rules/{}/{}] predicate \"{}\" 未注册，跳过", source, id, predId);
                return;
            }
        }

        boolean onRelease = o.has("onRelease") && o.get("onRelease").getAsBoolean();

        boolean overriding = !bundled.contains(id) && merged.containsKey(id);
        merged.put(id, new InteractionEntry(
            target.item(), target.tag(), trigger.item(), trigger.tag(),
            button, action, onRelease, predicate));
        if (source.equals("bundled")) bundled.add(id);
        if (overriding) {
            LivingItemManager.LOGGER.info("[interaction-rules/{}] 覆盖内置规则: {}", source, id);
        }
    }

    /** "minecraft:dirt" → item；"#minecraft:buttons" → tag；未填（trigger 省略）→ WILDCARD。 */
    @Nullable
    private static ItemOrTag parseItemOrTag(@Nullable String s, String field, String ruleId, String source) {
        if (s == null || s.isBlank()) {
            if (field.equals("trigger")) return ItemOrTag.WILDCARD;   // trigger 省略 = 通配
            LivingItemManager.LOGGER.warn("[interaction-rules/{}/{}] 缺 \"{}\"，跳过", source, ruleId, field);
            return null;
        }
        if (s.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
            if (id == null) {
                LivingItemManager.LOGGER.warn("[interaction-rules/{}/{}] {} 的 tag 非法: {}", source, ruleId, field, s);
                return null;
            }
            // ⚠️ tag 拼错无法在加载期发现（commonSetup 时 tag 未 resolve，匹配期恒 false）
            // —— 已知的静默点。启动测试会校验内置 JSON 里的 tag 真实存在（测试环境 tags 可用与否见测试注释）。
            return new ItemOrTag(null, TagKey.create(Registries.ITEM, id));
        }
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id == null) {
            LivingItemManager.LOGGER.warn("[interaction-rules/{}/{}] {} 的物品 ID 非法: {}", source, ruleId, field, s);
            return null;
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            LivingItemManager.LOGGER.warn("[interaction-rules/{}/{}] {} 的物品不存在: {}", source, ruleId, field, s);
            return null;
        }
        return new ItemOrTag(BuiltInRegistries.ITEM.get(id), null);
    }

    @Nullable
    private static Integer parseButton(@Nullable String s, String ruleId, String source) {
        if (s == null) {
            LivingItemManager.LOGGER.warn("[interaction-rules/{}/{}] 缺 \"button\"，跳过", source, ruleId);
            return null;
        }
        return switch (s) {
            case "left" -> 0;
            case "right" -> 1;
            case "middle" -> 2;
            default -> {
                LivingItemManager.LOGGER.warn(
                    "[interaction-rules/{}/{}] button 只能是 left/right/middle，得到: {}", source, ruleId, s);
                yield null;
            }
        };
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** item / tag 二选一的解析结果；WILDCARD 表示"未指定"（仅 trigger 合法省略）。 */
    private record ItemOrTag(@Nullable Item item, @Nullable TagKey<Item> tag) {
        static final ItemOrTag WILDCARD = new ItemOrTag(null, null);
    }
}
