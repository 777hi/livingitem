package com.qiqi.li.living.transfer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerRule;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.EdgeBehavior;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig.ContainerLayoutType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 容器规则配置文件管理器。
 *
 * <h2>三层来源（优先级从高到低）</h2>
 * <ol>
 *   <li><b>玩家移除</b> — 玩家显式删除的内置规则，{@link #REMOVED_IDS}；
 *       即使内置资源里还有，也不会生效</li>
 *   <li><b>玩家规则</b> — {@link #USER_RULES}，玩家通过指令注册/覆盖的条目，
 *       持久化在 {@code config/living_item/container_rules.json}</li>
 *   <li><b>内置规则</b> — 随 jar 打包的 {@code assets/living_item/container_rules.json}</li>
 * </ol>
 *
 * <p><b>增量语义：</b>配置文件只保存<b>玩家产生的差异</b>（新增、覆盖、删除），
 * 不把内置规则回写成副本。因此文件内容一目了然：里面每一条都是玩家真正改过的东西。</p>
 *
 * <p><b>开发期导出：</b>{@link #exportBundledFormat()} 把玩家规则按与内置资源
 * 完全一致的 JSON 格式写出到 {@code config/living_item/exported_rules.json}，
 * 开发者可将其内容合并进 {@code assets/living_item/container_rules.json} 后重新打包。
 * 打包后的正式环境不参与任何写入。</p>
 *
 * <h2>JSON 格式</h2>
 * <pre>{@code
 * {
 *   "version": 2,
 *   "rules": [
 *     {
 *       "containerId": "modid:container_type",
 *       "containerSize": 27,
 *       "columns": 9,
 *       "description": "原版箱子"
 *     }
 *   ],
 *   "removed": ["modid:unwanted_container"]
 * }
 * }</pre>
 */
public final class ContainerRuleConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerRuleConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配置文件名 */
    private static final String CONFIG_FILE = "container_rules.json";

    /** 导出文件名（开发期使用，格式与内置资源一致） */
    private static final String EXPORT_FILE = "exported_rules.json";

    /** 模组内置规则资源路径（随 jar 发布） */
    private static final String BUNDLED_RESOURCE = "/assets/living_item/" + CONFIG_FILE;

    /** 当前配置结构版本（v2 起支持 removed 字段） */
    private static final int CONFIG_VERSION = 2;

    /** 模组内置规则的 ID 集合，用于判断某条规则是否属于「内置」 */
    private static final Set<ResourceLocation> BUNDLED_IDS = new HashSet<>();

    /**
     * 玩家规则（新增或对内置规则的覆盖）。
     * <p>与 {@link ContainerCompatibilityConfig} 的注册表保持同步：启动时加载、
     * 通过指令修改时更新、{@link #save()} 时写出。</p>
     */
    private static final Map<ResourceLocation, ContainerRule> USER_RULES = new LinkedHashMap<>();

    /**
     * 被玩家显式移除的内置规则 ID。
     * <p>没有这个集合，玩家删掉的内置规则会在下次启动时被内置资源「复活」。</p>
     */
    private static final Set<ResourceLocation> REMOVED_IDS = new HashSet<>();

    /** 配置目录：config/living_item/ */
    private static Path configDir = null;

    private ContainerRuleConfig() {}

    /**
     * 初始化配置文件目录。
     * <p>应在模组初始化时调用。</p>
     */
    public static void init(Path configDirPath) {
        configDir = configDirPath.resolve("living_item");
        try {
            Files.createDirectories(configDir);
            LOGGER.info("Container rule config directory: {}", configDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create container rule config directory", e);
        }
    }

    /** 获取用户配置文件路径 */
    private static Path getConfigFile() {
        return configDir.resolve(CONFIG_FILE);
    }

    /** 获取导出文件路径（开发期使用） */
    public static Path getExportFile() {
        return configDir.resolve(EXPORT_FILE);
    }

    /**
     * 加载容器规则。
     *
     * <p>加载顺序（后者可覆盖前者）：</p>
     * <ol>
     *   <li>内置资源（同时登记 {@link #BUNDLED_IDS}）</li>
     *   <li>玩家配置中的 {@code removed} 列表 → 从注册表移除这些内置规则</li>
     *   <li>玩家配置中的 {@code rules} 列表 → 覆盖或新增</li>
     * </ol>
     *
     * <p><b>本方法是幂等的</b>：会先清空上一次加载产生的状态（注册表 + 玩家差异集），
     * 再从磁盘/资源重新构建。因此 {@code /livingitem container reload} 能真正反映
     * 磁盘上的当前内容——包括「玩家删除了某条内置规则」这种撤销场景。</p>
     */
    public static void load() {
        // 0. 清空旧状态，保证 reload 语义正确（否则已删除的内置规则无法恢复）
        resetState();

        // 1. 内置规则（项目级，随 jar 发布）
        loadFromResource(BUNDLED_RESOURCE, "bundled");

        // 2. 玩家差异（可选，配置目录）
        Path userFile = getConfigFile();
        if (Files.exists(userFile)) {
            // 显式 UTF-8：配置文件可能来自不同平台的玩家（跨平台交换不得依赖默认编码）
            try (Reader reader = new InputStreamReader(
                    new FileInputStream(userFile.toFile()), StandardCharsets.UTF_8)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    applyUserData(data);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load user container rule config", e);
            }
        }

        // 3. 汇总：便于作者/玩家一眼看出规则构成（社区贡献占比）
        LOGGER.info("Container rules ready: {} effective ({} bundled + {} community, {} bundled rule(s) removed)",
            ContainerCompatibilityConfig.getAllRules().size(), countBundled(), countUser(), REMOVED_IDS.size());
    }

    /**
     * 清空所有内存状态（注册表 + 内置标记 + 玩家差异集）。
     *
     * <p>只应由 {@link #load()} 与测试使用；生产代码不要单独调用，
     * 否则会丢失未经保存的玩家修改。</p>
     */
    static void resetState() {
        ContainerCompatibilityConfig.clearAllRules();
        BUNDLED_IDS.clear();
        USER_RULES.clear();
        REMOVED_IDS.clear();
    }

    /** 把玩家配置数据应用到注册表（删除 + 覆盖/新增） */
    private static void applyUserData(ConfigData data) {
        int removedCount = 0;
        int ruleCount = 0;
        int overrideCount = 0;
        int dupCount = 0;

        // 2a. 先应用删除：玩家显式移除了某条内置规则
        if (data.removed != null) {
            for (String raw : data.removed) {
                if (raw == null || raw.isBlank()) continue;
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id == null) continue;

                ContainerCompatibilityConfig.remove(id);
                REMOVED_IDS.add(id);
                USER_RULES.remove(id);
                removedCount++;
            }
        }

        // 2b. 再应用玩家规则：覆盖内置或纯新增
        if (data.rules != null) {
            for (RuleEntry entry : data.rules) {
                if (entry.containerId == null || entry.containerId.isBlank()) continue;
                ResourceLocation id = ResourceLocation.tryParse(entry.containerId);
                if (id == null) {
                    LOGGER.warn("Ignoring malformed container id in user config: {}", entry.containerId);
                    continue;
                }
                // 已被玩家移除的条目，以 removed 为准
                if (REMOVED_IDS.contains(id)) continue;

                ContainerRule rule = buildRule(entry);

                // 玩家规则内部重复定义：同一 ID 在同一文件里出现两次且数值不同 ⇒ 报 WARN
                ContainerRule prev = USER_RULES.get(id);
                if (prev != null && (prev.containerSize() != rule.containerSize()
                        || prev.columns() != rule.columns())) {
                    dupCount++;
                    LOGGER.warn("User config defines '{}' twice with different values — "
                            + "keeping later ({} slots, {} cols), was ({} slots, {} cols).",
                        id, rule.containerSize(), rule.columns(),
                        prev.containerSize(), prev.columns());
                }

                // 覆盖内置是预期行为（玩家修正错误数据），只记数、不告警
                if (isBundledRule(id) && prev == null) overrideCount++;

                ContainerCompatibilityConfig.register(id, rule);
                USER_RULES.put(id, rule);
                ruleCount++;
            }
        }

        if (removedCount > 0 || ruleCount > 0 || dupCount > 0) {
            LOGGER.info("Loaded user container rules: {} rule(s) ({} overriding bundled), {} removal(s)",
                ruleCount, overrideCount, removedCount);
        }
    }

    /**
     * 从类路径资源加载内置规则。
     *
     * <p>成功加载的条目 ID 会登记到 {@link #BUNDLED_IDS}，以便后续区分内置与玩家规则。</p>
     *
     * <p>对同一 ID 的重复定义会打 WARN（先到先得）——内置资源本该一个 ID 一条，
     * 出现重复通常意味着合并社区贡献时写重了，或两个来源给出了冲突数值。</p>
     *
     * @param resourcePath 资源路径（如 {@code /assets/living_item/container_rules.json}）
     * @param source 来源描述（用于日志）
     */
    private static void loadFromResource(String resourcePath, String source) {
        try (var in = ContainerRuleConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.warn("Bundled container rule resource not found: {}", resourcePath);
                return;
            }
            try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                loadRules(reader, source, resourcePath);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load container rules from {} ({})", source, resourcePath, e);
        }
    }

    /**
     * 解析一份规则 JSON 并注册（内置资源与「被作者覆盖后的内置资源」共用此路径）。
     *
     * <p>包级可见，便于测试直接喂入一份模拟的社区贡献快照，验证「作者整文件覆盖
     * 内置资源」后的加载语义——而不必真的替换 classpath 资源。</p>
     *
     * @param reader 规则 JSON 的读取器（调用方负责关闭）
     * @param source 来源描述（用于日志）
     * @param label 日志中显示的文件标识
     * @return 本次成功注册的条数
     */
    static int loadRules(Reader reader, String source, String label) {
        ConfigData data = GSON.fromJson(reader, ConfigData.class);
        if (data == null || data.rules == null) return 0;

        int loaded = 0;
        int conflicts = 0;
        for (RuleEntry entry : data.rules) {
            if (entry.containerId == null || entry.containerId.isBlank()) continue;
            ResourceLocation id = ResourceLocation.tryParse(entry.containerId);
            if (id == null) {
                LOGGER.warn("[{}] Ignoring malformed container id: {}", source, entry.containerId);
                continue;
            }

            // 已存在（玩家规则同名）则不覆盖；BUNDLED_IDS 始终登记，
            // 这样该 ID 的来源判定不会因玩家覆盖而漂移
            BUNDLED_IDS.add(id);
            var existing = ContainerCompatibilityConfig.findRule(id);
            if (existing.isPresent()) {
                // 重复定义：先到先得 + 警告（供作者发现合并冲突）
                if (differs(existing.get(), entry)) {
                    conflicts++;
                    LOGGER.warn("[{}] Duplicate container id '{}' with different values — "
                            + "keeping first ({} slots, {} cols), ignoring later ({} slots, {} cols). "
                            + "This usually means a merge conflict; please resolve it manually.",
                        source, id,
                        existing.get().containerSize(), existing.get().columns(),
                        entry.containerSize, entry.columns);
                }
                continue;
            }

            ContainerRule rule = buildRule(entry);
            ContainerCompatibilityConfig.register(id, rule);
            loaded++;
        }
        if (conflicts > 0) {
            LOGGER.warn("[{}] {} duplicate/conflicting rule(s) were skipped — "
                + "check the source file for merge mistakes.", source, conflicts);
        }
        LOGGER.info("Loaded {} container rules from {} ({})", loaded, source, label);
        return loaded;
    }

    /**
     * 判断 JSON 条目与已注册规则的数值是否不同。
     *
     * <p>只比较<b>布局相关</b>的字段（槽位数、列数）——描述文字不同不算冲突，
     * 否则合并社区贡献时会被大量「描述措辞不同」的假冲突淹没。</p>
     */
    private static boolean differs(ContainerRule existing, RuleEntry entry) {
        return existing.containerSize() != entry.containerSize
            || existing.columns() != entry.columns;
    }

    /** 该 ID 是否为模组内置规则（与是否被玩家覆盖无关） */
    public static boolean isBundledRule(ResourceLocation containerId) {
        return BUNDLED_IDS.contains(containerId);
    }

    /** 该 ID 是否被玩家显式移除 */
    public static boolean isRemovedByUser(ResourceLocation containerId) {
        return REMOVED_IDS.contains(containerId);
    }

    /**
     * 该 ID 是否有玩家产生的差异（新增、覆盖或删除）。
     *
     * @return {@code true} 表示玩家动过这条，应被持久化与导出
     */
    public static boolean isUserModified(ResourceLocation containerId) {
        return USER_RULES.containsKey(containerId) || REMOVED_IDS.contains(containerId);
    }

    /**
     * 获取玩家注册/覆盖的规则（不含删除记录）。
     *
     * @return 规则映射（按 ID 排序，保证导出结果稳定可比对）
     */
    public static Map<ResourceLocation, ContainerRule> getUserRules() {
        List<ResourceLocation> ids = new ArrayList<>(USER_RULES.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));

        Map<ResourceLocation, ContainerRule> result = new LinkedHashMap<>();
        for (ResourceLocation id : ids) {
            result.put(id, USER_RULES.get(id));
        }
        return result;
    }

    /** 获取被玩家显式移除的内置规则 ID（已排序） */
    public static List<ResourceLocation> getRemovedIds() {
        List<ResourceLocation> ids = new ArrayList<>(REMOVED_IDS);
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        return ids;
    }

    /**
     * 保存玩家产生的差异到配置文件。
     *
     * <p>只写出「玩家规则」与「玩家移除记录」，不把内置规则回写成副本。
     * 若两者皆空，则写出一个空的差异文件。</p>
     */
    public static void save() {
        Path file = getConfigFile();
        ConfigData data = new ConfigData();
        data.version = CONFIG_VERSION;
        data.rules = new ArrayList<>();

        for (var entry : getUserRules().entrySet()) {
            RuleEntry re = new RuleEntry();
            re.containerId = entry.getKey().toString();
            re.containerSize = entry.getValue().containerSize();
            re.columns = entry.getValue().columns();
            re.description = entry.getValue().description();
            data.rules.add(re);
        }

        data.removed = new ArrayList<>();
        for (ResourceLocation id : getRemovedIds()) {
            data.removed.add(id.toString());
        }

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save container rule config", e);
        }
    }

    /**
     * 导出<b>当前全部生效规则</b>，格式与模组内置资源
     * {@code assets/living_item/container_rules.json} 完全一致。
     *
     * <p>导出内容 = 内存中生效的规则全集（内置 + 玩家新增 − 玩家删除），
     * 因此产物是一份<b>完整快照</b>，可以被直接复制覆盖到
     * {@code src/main/resources/assets/living_item/container_rules.json}，
     * 不必逐条摘录、也不必手工合并。</p>
     *
     * <p><b>玩家协作流程</b>：</p>
     * <ol>
     *   <li>玩家在游戏里对着自己装的模组容器 {@code register}，把容量/列数校准</li>
     *   <li>执行本指令导出 → {@code config/living_item/exported_rules.json}</li>
     *   <li>把该文件发给模组作者</li>
     *   <li>作者用它对内置资源做<b>文件级覆盖</b>，重新打包 ⇒ 兼容性随包发布给所有玩家</li>
     * </ol>
     *
     * <p>因导出的是全量生效规则，其中已包含作者原有的内置条目，
     * 所以覆盖不会丢失内置数据。</p>
     *
     * <p>导出不含 {@code removed} 字段——删除是玩家本地偏好，不应影响内置资源；
     * 玩家删掉的内置规则只是不出现在这份快照里。本方法只读取内存状态，
     * 不修改任何游戏数据，正式环境调用无副作用。</p>
     *
     * @return 导出的条目数；写出失败返回 {@code -1}
     */
    public static int exportBundledFormat() {
        Path file = getExportFile();

        // 导出全量生效规则：直接对注册表取快照，按 ID 排序保证结果稳定可比对
        List<ResourceLocation> ids = new ArrayList<>();
        for (var entry : ContainerCompatibilityConfig.getAllRules()) {
            ids.add(entry.getKey());
        }
        ids.sort(Comparator.comparing(ResourceLocation::toString));

        ConfigData data = new ConfigData();
        data.version = 1;
        data.rules = new ArrayList<>();
        for (ResourceLocation id : ids) {
            ContainerRule rule = ContainerCompatibilityConfig.findRule(id).orElse(null);
            if (rule == null) continue;
            RuleEntry re = new RuleEntry();
            re.containerId = id.toString();
            re.containerSize = rule.containerSize();
            re.columns = rule.columns();
            re.description = rule.description();
            data.rules.add(re);
        }

        try {
            Files.createDirectories(file.getParent());
            // 显式 UTF-8：这个文件要发给作者（跨平台），编码必须确定
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            LOGGER.info("Exported {} effective container rules ({} bundled, {} user) to {}",
                ids.size(), countBundled(), countUser(), file);
        } catch (IOException e) {
            LOGGER.error("Failed to export container rules to {}", file, e);
            return -1;
        }
        return ids.size();
    }

    /** 当前生效规则里来自内置资源的条数（含被玩家覆盖的条目） */
    public static int countBundled() {
        int n = 0;
        for (var entry : ContainerCompatibilityConfig.getAllRules()) {
            if (isBundledRule(entry.getKey())) n++;
        }
        return n;
    }

    /** 当前生效规则里由玩家新增（非内置）的条数 */
    public static int countUser() {
        int n = 0;
        for (var entry : ContainerCompatibilityConfig.getAllRules()) {
            if (!isBundledRule(entry.getKey())) n++;
        }
        return n;
    }

    /**
     * 添加或覆盖一条容器规则并保存。
     *
     * <p>允许覆盖内置规则——内置数据可能有误（如注册名拼写、槽位数编造），
     * 玩家在游戏中实测后应当能够修正它。</p>
     *
     * @param containerId 容器 ID
     * @param rule 容器规则
     * @return {@code true} 表示这是对已有规则的覆盖，{@code false} 表示新增
     */
    public static boolean addAndSave(ResourceLocation containerId, ContainerRule rule) {
        boolean overwrite = ContainerCompatibilityConfig.findRule(containerId).isPresent();

        ContainerCompatibilityConfig.register(containerId, rule);
        REMOVED_IDS.remove(containerId);
        USER_RULES.put(containerId, rule);
        save();

        if (overwrite) {
            LOGGER.info("Overrode container rule: {} ({} slots, {} cols, was bundled={})",
                containerId, rule.containerSize(), rule.columns(), isBundledRule(containerId));
        } else {
            LOGGER.info("Registered container rule: {} ({} slots, {} cols)",
                containerId, rule.containerSize(), rule.columns());
        }
        return overwrite;
    }

    /**
     * 移除一条容器规则并保存。
     *
     * <p>若该规则属于模组内置资源，会记入 {@code removed} 列表，
     * 否则下次启动会被内置资源重新加载回来。</p>
     *
     * @param containerId 容器 ID
     * @return 是否成功移除
     */
    public static boolean removeAndSave(ResourceLocation containerId) {
        boolean wasBundled = isBundledRule(containerId);
        boolean removed = ContainerCompatibilityConfig.remove(containerId);
        USER_RULES.remove(containerId);

        // 内置规则必须留下删除记录，才能真正"删掉"
        if (wasBundled) {
            REMOVED_IDS.add(containerId);
        } else {
            REMOVED_IDS.remove(containerId);
        }

        if (removed || wasBundled) {
            save();
            LOGGER.info("Removed container rule: {} (bundled={})", containerId, wasBundled);
        }
        return removed;
    }

    /**
     * 根据配置条目构建容器规则。
     * <p>自动生成标准的方向映射和边界行为。</p>
     */
    private static ContainerRule buildRule(RuleEntry entry) {
        int cols = entry.columns > 0 ? entry.columns : ContainerCompatibilityConfig.resolveColumns(entry.containerSize);
        String desc = (entry.description != null && !entry.description.isBlank())
            ? entry.description : "自定义注册 " + entry.containerId;

        return ContainerRule.builder()
            .containerSize(entry.containerSize)
            .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
            .columns(cols)
            .validHostSlots(range(0, entry.containerSize - 1))
            .directionMapping(Pos2D.LEFT, -1)
            .directionMapping(Pos2D.RIGHT, 1)
            .directionMapping(Pos2D.UP, -cols)
            .directionMapping(Pos2D.DOWN, cols)
            .edgeBehavior(EdgeBehavior.INVALIDATE)
            .description(desc)
            .build();
    }

    private static List<Integer> range(int start, int end) {
        List<Integer> list = new ArrayList<>();
        for (int i = start; i <= end; i++) list.add(i);
        return list;
    }

    // ── JSON 数据结构 ────────────────────────────────────────

    /** 配置文件顶层结构 */
    private static class ConfigData {
        int version = CONFIG_VERSION;
        List<RuleEntry> rules = new ArrayList<>();
        /** 被玩家显式移除的容器 ID（v2 新增） */
        List<String> removed = new ArrayList<>();
    }

    /** 单条规则条目 */
    private static class RuleEntry {
        String containerId;
        int containerSize;
        int columns;
        String description;
    }
}
