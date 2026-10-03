package com.qiqi.li.living.domain.water;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 流体转化表（F4，2026-10-03）—— 源格物品转化的 JSON 数据驱动。
 *
 * <p>机制三（{@code ContainerFluidData.transformSourceItems}）：流体拍在<b>源格</b>问行为
 * 「格上物品是否转化」；本表是流体侧行为的数据层 —— 各流体行为
 * （如 {@code WaterRegistration} 的水行为）的 {@code transformItem} 委托到
 * {@link #transform}，按（流体类型，输入物品）查表。</p>
 *
 * <p><b>三层来源与增量语义</b>与 {@code InteractionRuleConfig} 同构（照抄不发明第二套）：
 * 内置 {@code assets/living_item/fluid_transforms.json} + 玩家差异
 * {@code config/living_item/fluid_transforms.json}（{@code transforms[]} 追加/按 id 覆盖、
 * {@code removed[]} 删除）；reload 幂等（整表重建）。</p>
 *
 * <p><b>条目格式</b>：</p>
 * <pre>{ "id": "water_empty_bucket", "fluid": "minecraft:water",
 *   "input": "minecraft:bucket", "output": "minecraft:water_bucket" }</pre>
 *
 * <p><b>转化规则（口径，2026-10-03 拍板）</b>：</p>
 * <ul>
 *   <li><b>整槽转化</b>，且仅当 {@code 数量 ≤ 产物最大堆叠} 才执行 —— 缩容等待
 *       （空桶×16 永不转化：水桶最大堆叠 1，整槽无法等量替换；拆分后立即转化）；</li>
 *   <li><b>等量替换</b>：输出数量 = 输入数量（无 count 字段 —— 等比/倍增留给真实需求）；</li>
 *   <li>活物品不转化 —— 活物品进源格会被挤没（引擎先于转化），此处再防御一层；</li>
 *   <li>转化节拍：无（全部即时，≤1t）——「浸泡感」由渲染涟漪补（idea.md §三）。
 *       JSON 暂不解析 interval 字段：引擎接缝 {@code transformItem} 无节拍参数，
 *       等首个真需要节拍的条目出现再扩（不为假想需求做扩展点）。</li>
 * </ul>
 *
 * <p><b>失败语义（沉默即缺陷）</b>：与 {@code InteractionRuleConfig} 同 —— 版本不识别 /
 * 缺字段 / 坏值 / 未知流体或物品 ID / 未知字段 ⇒ WARN 并跳过该条，绝不崩游戏。</p>
 */
public final class FluidTransformTable {

    private static final String CONFIG_FILE = "fluid_transforms.json";
    private static final String BUNDLED_RESOURCE = "/assets/living_item/" + CONFIG_FILE;
    private static final int CONFIG_VERSION = 1;

    /** 条目对象允许的全部字段 —— 多出的都是拼写错误，WARN。 */
    private static final Set<String> KNOWN_KEYS = Set.of("id", "fluid", "input", "output");

    /** 一条转化：流体类型 + 输入物品 → 输出物品（等量替换）。 */
    public record TransformEntry(String id, FluidType fluid, Item input, Item output) {}

    /** 合并后的最终表（id → 条目，顺序 = 注册顺序）。 */
    private static final Map<String, TransformEntry> ENTRIES = new LinkedHashMap<>();
    /** 内置条目 id 集合 —— {@code list} 指令区分来源用。 */
    private static final Set<String> BUNDLED_IDS = new HashSet<>();
    /** 查询索引：流体类型 → 输入物品 → 条目（transform 热路径 O(1)，免遍历）。 */
    private static final Map<FluidType, Map<Item, TransformEntry>> INDEX = new HashMap<>();

    private static Path configDir;

    private FluidTransformTable() {}

    public static void init(Path dir) {
        configDir = dir;
    }

    /**
     * 重载全部转化（幂等）：内置 + 玩家差异合并后整体重建（含查询索引）。
     */
    public static void load() {
        Map<String, TransformEntry> merged = new LinkedHashMap<>();
        Set<String> bundled = new HashSet<>();

        parseResource(BUNDLED_RESOURCE, "bundled", merged, bundled);
        parseUserFile(merged, bundled);

        ENTRIES.clear();
        ENTRIES.putAll(merged);
        BUNDLED_IDS.clear();
        BUNDLED_IDS.addAll(bundled);
        rebuildIndex();

        LivingItemManager.LOGGER.info("Fluid transforms ready: {} effective ({} bundled + {} user)",
            ENTRIES.size(), BUNDLED_IDS.size(), ENTRIES.size() - BUNDLED_IDS.size());
    }

    /** 由 ENTRIES 重建查询索引（load 幂等的一部分）。 */
    private static void rebuildIndex() {
        INDEX.clear();
        for (TransformEntry e : ENTRIES.values()) {
            INDEX.computeIfAbsent(e.fluid(), k -> new HashMap<>()).put(e.input(), e);
        }
    }

    /** 读取字符串字段；缺失 / 非字符串返回 {@code null}。 */
    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** 当前生效条数（{@code /livingitem transforms list} 用）。 */
    public static int entryCount() {
        return ENTRIES.size();
    }

    /** 内置条目数（含被玩家覆盖的）。 */
    public static int bundledCount() {
        return BUNDLED_IDS.size();
    }

    /** 逐条描述当前生效转化（list 指令用），标注来源。 */
    public static List<String> describeEntries() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, TransformEntry> e : ENTRIES.entrySet()) {
            TransformEntry t = e.getValue();
            out.add("[" + e.getKey() + "]"
                + (BUNDLED_IDS.contains(e.getKey()) ? "(bundled)" : "(user)")
                + " " + key(t.fluid) + ": " + name(t.input()) + " -> " + name(t.output()));
        }
        return out;
    }

    /**
     * 转化判定（流体行为的 {@code transformItem} 委托到这里）。
     *
     * @return 转化后的物品（等量）；不转化返回 {@code null}
     */
    @Nullable
    public static ItemStack transform(FluidType fluid, ItemStack stack) {
        if (stack.isEmpty() || LivingItemManager.isLivingItem(stack)) return null;
        Map<Item, TransformEntry> byInput = INDEX.get(fluid);
        if (byInput == null) return null;
        TransformEntry entry = byInput.get(stack.getItem());
        if (entry == null) return null;
        // 缩容等待：整槽等量替换要求 产物最大堆叠 ≥ 当前数量
        if (stack.getCount() > new ItemStack(entry.output()).getMaxStackSize()) return null;
        return new ItemStack(entry.output(), stack.getCount());
    }

    // ── 解析（与 InteractionRuleConfig 同构）──────────────────

    private static void parseResource(String resourcePath, String source,
            Map<String, TransformEntry> merged, Set<String> bundled) {
        try (var in = FluidTransformTable.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LivingItemManager.LOGGER.warn("Bundled fluid transform resource not found: {}", resourcePath);
                return;
            }
            parseRoot(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)), source, merged, bundled);
        } catch (Exception e) {
            LivingItemManager.LOGGER.error("Failed to load fluid transforms from {}", source, e);
        }
    }

    private static void parseUserFile(Map<String, TransformEntry> merged, Set<String> bundled) {
        if (configDir == null) return;
        Path file = configDir.resolve(CONFIG_FILE);
        if (!Files.exists(file)) return;           // 没写过 = 没有差异，正常
        try (Reader reader = new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {
            parseRoot(JsonParser.parseReader(reader), "user", merged, bundled);
        } catch (Exception e) {
            LivingItemManager.LOGGER.error("Failed to load user fluid transforms from {}", file, e);
        }
    }

    /** 根对象：版本检查 → removed 先应用 → transforms 逐条解析。 */
    private static void parseRoot(JsonElement rootEl, String source,
            Map<String, TransformEntry> merged, Set<String> bundled) {
        if (!rootEl.isJsonObject()) {
            LivingItemManager.LOGGER.warn("[fluid-transforms/{}] 根节点不是对象，跳过整个文件", source);
            return;
        }
        JsonObject root = rootEl.getAsJsonObject();
        JsonElement version = root.get("version");
        if (version == null || version.getAsInt() != CONFIG_VERSION) {
            LivingItemManager.LOGGER.warn(
                "[fluid-transforms/{}] version 缺失或不识别（期望 {}），跳过整个文件", source, CONFIG_VERSION);
            return;
        }

        if (root.has("removed") && root.get("removed").isJsonArray()) {
            for (JsonElement e : root.get("removed").getAsJsonArray()) {
                String id = e.isJsonPrimitive() ? e.getAsString() : null;
                if (id != null && merged.remove(id) != null) {
                    LivingItemManager.LOGGER.info("[fluid-transforms/{}] removed: {}", source, id);
                }
            }
        }

        if (root.has("transforms") && root.get("transforms").isJsonArray()) {
            for (JsonElement e : root.get("transforms").getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    LivingItemManager.LOGGER.warn("[fluid-transforms/{}] transforms 里出现非对象元素，跳过", source);
                    continue;
                }
                parseEntry(e.getAsJsonObject(), source, merged, bundled);
            }
        }
    }

    private static void parseEntry(JsonObject obj, String source,
            Map<String, TransformEntry> merged, Set<String> bundled) {
        for (String key : obj.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                LivingItemManager.LOGGER.warn("[fluid-transforms/{}] 条目含未知字段 \"{}\"（拼写错误？），跳过", source, key);
                return;
            }
        }
        String id = string(obj, "id");
        String fluidId = string(obj, "fluid");
        String inputId = string(obj, "input");
        String outputId = string(obj, "output");
        if (id == null || fluidId == null || inputId == null || outputId == null) {
            LivingItemManager.LOGGER.warn("[fluid-transforms/{}] 条目缺 id/fluid/input/output 之一，跳过", source);
            return;
        }

        FluidType fluid = resolveFluid(fluidId);
        Item input = resolveItem(inputId);
        Item output = resolveItem(outputId);
        if (fluid == null || input == null || output == null
                || input == Items.AIR || output == Items.AIR) {
            LivingItemManager.LOGGER.warn("[fluid-transforms/{}] 条目 {} 引用未知流体/物品（或解析成 AIR），跳过", source, id);
            return;
        }

        boolean replaced = merged.put(id, new TransformEntry(id, fluid, input, output)) != null;
        if ("bundled".equals(source)) bundled.add(id);
        if (replaced) {
            LivingItemManager.LOGGER.info("[fluid-transforms/{}] 覆盖条目: {}", source, id);
        }
    }

    private static FluidType resolveFluid(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : NeoForgeRegistries.FLUID_TYPES.getOptional(rl).orElse(null);
    }

    private static Item resolveItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    private static String key(FluidType fluid) {
        ResourceLocation rl = NeoForgeRegistries.FLUID_TYPES.getKey(fluid);
        return rl != null ? rl.toString() : fluid.toString();
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

}
