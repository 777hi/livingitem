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

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 容器规则配置文件管理器。
 *
 * <p>规则来源（优先级从高到低）：</p>
 * <ol>
 *   <li><b>模组自带规则</b> — 随 jar 打包的 {@code assets/living_item/container_rules.json}，
 *       社区贡献的兼容性规则在此更新，可通过 PR 提交</li>
 *   <li><b>玩家本地覆盖</b> — {@code config/living_item/container_rules.json}，
 *       玩家通过指令注册的额外规则，不会丢失</li>
 * </ol>
 *
 * <p>JSON 格式：</p>
 * <pre>{@code
 * {
 *   "version": 1,
 *   "rules": [
 *     {
 *       "containerId": "modid:container_type",
 *       "containerSize": 27,
 *       "columns": 9,
 *       "description": "原版箱子"
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class ContainerRuleConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerRuleConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配置文件名 */
    private static final String CONFIG_FILE = "container_rules.json";

    /** 模组自带规则资源路径（随 jar 发布） */
    private static final String BUNDLED_RESOURCE = "/assets/living_item/" + CONFIG_FILE;

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

    /**
     * 加载容器规则。
     *
     * <p>加载顺序：</p>
     * <ol>
     *   <li>从模组自带的 {@code assets/living_item/container_rules.json} 加载（始终加载）</li>
     *   <li>从 {@code config/living_item/container_rules.json} 加载玩家额外注册的规则（可选）</li>
     * </ol>
     *
     * <p>模组自带的规则是项目级的，随 jar 打包发布；
     * 玩家本地规则存储在配置目录中，不会被覆盖。</p>
     */
    public static void load() {
        // 1. 加载模组自带规则（项目级，随 jar 发布）
        loadFromResource(BUNDLED_RESOURCE, "bundled");

        // 2. 加载玩家本地规则（可选，配置目录）
        Path userFile = getConfigFile();
        if (Files.exists(userFile)) {
            try (FileReader reader = new FileReader(userFile.toFile())) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data == null || data.rules == null) return;

                int loaded = 0;
                for (RuleEntry entry : data.rules) {
                    if (entry.containerId == null || entry.containerId.isBlank()) continue;
                    ResourceLocation id = ResourceLocation.parse(entry.containerId);
                    if (ContainerCompatibilityConfig.findRule(id).isPresent()) continue;

                    ContainerRule rule = buildRule(entry);
                    ContainerCompatibilityConfig.register(id, rule);
                    loaded++;
                }
                if (loaded > 0) {
                    LOGGER.info("Loaded {} user container rules from config", loaded);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load user container rule config", e);
            }
        }
    }

    /**
     * 从类路径资源加载规则。
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
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data == null || data.rules == null) return;

                int loaded = 0;
                for (RuleEntry entry : data.rules) {
                    if (entry.containerId == null || entry.containerId.isBlank()) continue;
                    ResourceLocation id = ResourceLocation.parse(entry.containerId);
                    if (ContainerCompatibilityConfig.findRule(id).isPresent()) continue;

                    ContainerRule rule = buildRule(entry);
                    ContainerCompatibilityConfig.register(id, rule);
                    loaded++;
                }
                LOGGER.info("Loaded {} container rules from {} ({})", loaded, source, resourcePath);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load container rules from {} ({})", source, resourcePath, e);
        }
    }

    /**
     * 保存当前所有容器规则到配置文件。
     * <p>包括默认规则和玩家注册的规则。</p>
     */
    public static void save() {
        Path file = getConfigFile();
        ConfigData data = new ConfigData();
        data.version = 1;
        data.rules = new ArrayList<>();

        for (var entry : ContainerCompatibilityConfig.getAllRules()) {
            ResourceLocation id = entry.getKey();
            ContainerRule rule = entry.getValue();
            RuleEntry re = new RuleEntry();
            re.containerId = id.toString();
            re.containerSize = rule.containerSize();
            re.columns = rule.columns();
            re.description = rule.description();
            data.rules.add(re);
        }

        try (FileWriter writer = new FileWriter(file.toFile())) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save container rule config", e);
        }
    }

    /**
     * 添加一条容器规则并保存到配置文件。
     *
     * @param containerId 容器 ID
     * @param rule 容器规则
     */
    public static void addAndSave(ResourceLocation containerId, ContainerRule rule) {
        ContainerCompatibilityConfig.register(containerId, rule);
        save();
        LOGGER.info("Registered container rule: {} ({} slots, {} cols)", containerId, rule.containerSize(), rule.columns());
    }

    /**
     * 移除一条容器规则并保存到配置文件。
     *
     * @param containerId 容器 ID
     * @return 是否成功移除
     */
    public static boolean removeAndSave(ResourceLocation containerId) {
        boolean removed = ContainerCompatibilityConfig.remove(containerId);
        if (removed) {
            save();
            LOGGER.info("Removed container rule: {}", containerId);
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
        int version = 1;
        List<RuleEntry> rules = new ArrayList<>();
    }

    /** 单条规则条目 */
    private static class RuleEntry {
        String containerId;
        int containerSize;
        int columns;
        String description;
    }
}