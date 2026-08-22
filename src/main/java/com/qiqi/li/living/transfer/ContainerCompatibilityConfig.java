package com.qiqi.li.living.transfer;

import com.qiqi.li.living.model.Pos2D;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 容器兼容性配置 —— 为不同容器类型定义槽位解析规则。
 *
 * 职责：
 * 1. 注册每种容器类型的解析规则（ContainerRule）
 * 2. 根据容器 ID 查找对应的规则
 *
 * 内置规则：
 * - minecraft:chest（27 格箱子）：标准 9 列矩形，越界无效
 * - minecraft:double_chest（54 格大箱子）：标准 9 列矩形，越界环绕
 * - minecraft:hopper（5 格漏斗）：线性布局，仅中间 3 格可宿主
 * - ironchests:iron_chest（45 格铁箱）：标准 9 列矩形
 *
 * 扩展方式：
 * - 通过 register() 方法注册自定义规则
 * - 通过 loadFromJson() 从 JSON 文件加载（预留接口）
 *
 * 规则匹配流程：
 *   identifyContainer() → ResourceLocation → findRule() → ContainerRule
 */
public final class ContainerCompatibilityConfig {

    /** 容器规则注册表（容器 ID → 规则） */
    private static final Map<ResourceLocation, ContainerRule> RULES = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCompatibilityConfig.class);

    static {
        initializeDefaultRules();
    }

    private ContainerCompatibilityConfig() {}

    /** 初始化内置的容器兼容性规则 */
    private static void initializeDefaultRules() {
        try {
            register(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"), ContainerRule.builder()
                .containerSize(27)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 26))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -9)
                .directionMapping(Pos2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("minecraft", "double_chest"), ContainerRule.builder()
                .containerSize(54)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 53))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -9)
                .directionMapping(Pos2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.WRAP)
                .crossBlockEntitySupport(true)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("minecraft", "hopper"), ContainerRule.builder()
                .containerSize(5)
                .layoutType(ContainerLayoutType.LINEAR)
                .validHostSlots(Arrays.asList(1, 2, 3))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -1)
                .directionMapping(Pos2D.DOWN, -1)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("ironchests", "iron_chest"), ContainerRule.builder()
                .containerSize(45)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .columns(9)
                .validHostSlots(range(0, 44))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -9)
                .directionMapping(Pos2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("ironchests", "diamond_chest"), ContainerRule.builder()
                .containerSize(108)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .columns(12)
                .validHostSlots(range(0, 107))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -12)
                .directionMapping(Pos2D.DOWN, 12)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", "backpack"), ContainerRule.builder()
                .containerSize(120)
                .layoutType(ContainerLayoutType.RECTANGULAR_CUSTOM)
                .columns(12)
                .validHostSlots(range(0, 119))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -12)
                .directionMapping(Pos2D.DOWN, 12)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .description("精妙背包 12×10")
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", "backpack"), ContainerRule.builder()
                .containerSize(108)
                .layoutType(ContainerLayoutType.RECTANGULAR_CUSTOM)
                .columns(12)
                .validHostSlots(range(0, 107))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -12)
                .directionMapping(Pos2D.DOWN, 12)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .description("精妙背包 12×9")
                .build()
            );

            LOGGER.info("Initialized {} default container compatibility rules", RULES.size());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize default container rules", e);
        }
    }

    /** 注册容器规则 */
    public static void register(ResourceLocation containerId, ContainerRule rule) {
        RULES.put(containerId, rule);
    }

    /** 根据容器 ID 查找规则 */
    public static Optional<ContainerRule> findRule(ResourceLocation containerId) {
        return Optional.ofNullable(RULES.get(containerId));
    }

    public static Optional<ContainerRule> findRuleBySize(int containerSize) {
        for (var entry : RULES.entrySet()) {
            if (entry.getValue().containerSize() == containerSize) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * 查找或自动生成容器规则。
     *
     * 优先查找已注册的规则，如果没有找到，则根据 IItemHandler.getSlots() 自动推断
     * 标准矩形布局（无需手动为每个容器类型注册规则）。
     *
     * @param containerSize 容器槽位数
     * @return 容器规则（永不返回 null）
     */
    public static ContainerRule findOrGenerateRule(int containerSize) {
        return findRuleBySize(containerSize).orElseGet(() -> generateStandardRule(containerSize));
    }

    /**
     * 根据槽位数自动生成标准矩形布局规则。
     *
     * 列数推断规则：尝试从 9 列开始，找到能整除槽位数的列宽。
     * 例如：27→9列, 54→9列, 45→9列, 108→12列, 81→9列。
     */
    private static ContainerRule generateStandardRule(int containerSize) {
        int columns = resolveColumns(containerSize);
        return ContainerRule.builder()
            .containerSize(containerSize)
            .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
            .columns(columns)
            .validHostSlots(range(0, containerSize - 1))
            .directionMapping(Pos2D.LEFT, -1)
            .directionMapping(Pos2D.RIGHT, 1)
            .directionMapping(Pos2D.UP, -columns)
            .directionMapping(Pos2D.DOWN, columns)
            .edgeBehavior(EdgeBehavior.INVALIDATE)
            .description("auto-generated from slot count " + containerSize)
            .build();
    }

    /**
     * 根据槽位数推断列数。
     * 从 min(slotCount, 13) 列向下尝试，找到能整除的列宽；否则默认 9 列。
     */
    private static int resolveColumns(int slotCount) {
        for (int w = Math.min(slotCount, 13); w >= 1; w--) {
            if (slotCount % w == 0) return w;
        }
        return 9;
    }

    public static Optional<ContainerRule> findRuleByNamespaceAndKeyword(String namespace, String path) {
        for (var entry : RULES.entrySet()) {
            ResourceLocation key = entry.getKey();
            if (key.getNamespace().equals(namespace)) {
                String rulePath = key.getPath();
                String[] ruleParts = rulePath.split("_|-");
                String[] pathParts = path.split("_|-");
                for (String rulePart : ruleParts) {
                    for (String pathPart : pathParts) {
                        if (rulePart.equalsIgnoreCase(pathPart) && !rulePart.isEmpty()) {
                            return Optional.of(entry.getValue());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 获取所有已注册规则（不可变视图） */
    public static Set<Map.Entry<ResourceLocation, ContainerRule>> getAllRules() {
        return Collections.unmodifiableSet(RULES.entrySet());
    }

    /** 从 JSON 文件加载规则（预留接口） */
    public static void loadFromJson(String jsonPath) {
    }

    /** 生成整数范围列表 */
    private static List<Integer> range(int start, int end) {
        List<Integer> list = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            list.add(i);
        }
        return list;
    }

    /** 容器布局类型 */
    public enum ContainerLayoutType {
        /** 标准 9 列矩形 */
        RECTANGULAR_STANDARD,
        /** 自定义列宽矩形 */
        RECTANGULAR_CUSTOM,
        /** 线性布局（单行） */
        LINEAR,
        /** 不规则布局 */
        IRREGULAR
    }

    /**
     * 边界行为 —— 当槽位索引超出容器范围时的处理方式。
     *
     * INVALIDATE: 返回 -1（无效槽位，默认行为）
     * WRAP: 环绕到容器另一端（如大箱子左右环绕）
     * CLAMP: 钳制到最近的有效边界
     * SKIP: 跳过此方向
     */
    public enum EdgeBehavior {
        INVALIDATE,
        WRAP,
        CLAMP,
        SKIP
    }

    /**
     * 容器规则 —— 定义特定容器类型的槽位解析规则。
     *
     * @param containerSize 容器大小
     * @param layoutType 布局类型
     * @param validHostSlots 可以作为活物品宿主的槽位列表
     * @param directionMappings 方向到偏移量的映射（如 LEFT→-1, DOWN→+9）
     * @param edgeBehavior 边界行为
     * @param crossBlockEntitySupport 是否支持跨方块实体（如大箱子）
     * @param description 规则描述
     */
    public record ContainerRule(
        int containerSize,
        ContainerLayoutType layoutType,
        int columns,
        List<Integer> validHostSlots,
        Map<Pos2D, Integer> directionMappings,
        EdgeBehavior edgeBehavior,
        boolean crossBlockEntitySupport,
        String description
    ) {
        /** 创建规则构建器 */
        public static Builder builder() {
            return new Builder();
        }

        /** 检查指定槽位是否可以作为活物品宿主 */
        public boolean isValidHostSlot(int slot) {
            return validHostSlots.contains(slot);
        }

        /** 获取指定方向的偏移量（无映射返回 0） */
        public int getDirectionOffset(Pos2D direction) {
            return directionMappings.getOrDefault(direction, 0);
        }
    }

    /** 容器规则构建器（支持链式调用） */
    public static class Builder {
        private int containerSize = 0;
        private ContainerLayoutType layoutType = ContainerLayoutType.RECTANGULAR_STANDARD;
        private int columns = 9;
        private List<Integer> validHostSlots = new ArrayList<>();
        private Map<Pos2D, Integer> directionMappings = new HashMap<>();
        private EdgeBehavior edgeBehavior = EdgeBehavior.INVALIDATE;
        private boolean crossBlockEntitySupport = false;
        private String description = "";

        public Builder containerSize(int size) {
            this.containerSize = size;
            return this;
        }

        public Builder layoutType(ContainerLayoutType type) {
            this.layoutType = type;
            return this;
        }

        public Builder columns(int cols) {
            this.columns = cols;
            return this;
        }

        public Builder validHostSlots(List<Integer> slots) {
            this.validHostSlots = slots;
            return this;
        }

        public Builder directionMapping(Pos2D direction, int offset) {
            this.directionMappings.put(direction, offset);
            return this;
        }

        public Builder edgeBehavior(EdgeBehavior behavior) {
            this.edgeBehavior = behavior;
            return this;
        }

        public Builder crossBlockEntitySupport(boolean support) {
            this.crossBlockEntitySupport = support;
            return this;
        }

        public Builder description(String desc) {
            this.description = desc;
            return this;
        }

        /** 构建规则（校验必填字段） */
        public ContainerRule build() {
            if (containerSize <= 0) {
                throw new IllegalStateException("Container size must be positive");
            }
            if (validHostSlots.isEmpty()) {
                validHostSlots = range(0, containerSize - 1);
            }
            return new ContainerRule(
                containerSize, layoutType, columns, validHostSlots,
                directionMappings, edgeBehavior, crossBlockEntitySupport, description
            );
        }
    }
}