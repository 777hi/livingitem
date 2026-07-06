package com.qiqi.li.living.core.config;

import com.qiqi.li.living.core.Direction2D;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 容器兼容性配置 - 声明式方式定义已知容器的行为
 * 
 * 使用场景：
 * 1. 已知特定模组的容器特性，可以精确配置
 * 2. 避免运行时探测的性能开销
 * 3. 为用户提供可编辑的配置文件
 */
public final class ContainerCompatibilityConfig {

    private static final Map<ResourceLocation, ContainerRule> RULES = new HashMap<>();
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCompatibilityConfig.class);

    static {
        initializeDefaultRules();
    }

    private ContainerCompatibilityConfig() {}

    /**
     * 初始化默认规则（覆盖常见容器）
     */
    private static void initializeDefaultRules() {
        try {
            // 原版箱子
            register(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"), ContainerRule.builder()
                .containerSize(27)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 26))
                .directionMapping(Direction2D.LEFT, -1)
                .directionMapping(Direction2D.RIGHT, 1)
                .directionMapping(Direction2D.UP, -9)
                .directionMapping(Direction2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            // 原版大箱子（双箱子）
            register(ResourceLocation.fromNamespaceAndPath("minecraft", "double_chest"), ContainerRule.builder()
                .containerSize(54)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 53))
                .directionMapping(Direction2D.LEFT, -1)
                .directionMapping(Direction2D.RIGHT, 1)
                .directionMapping(Direction2D.UP, -9)
                .directionMapping(Direction2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.WRAP)  // 大箱子特殊处理：跨BlockEntity边界
                .crossBlockEntitySupport(true)
                .build()
            );

            // 原版漏斗
            register(ResourceLocation.fromNamespaceAndPath("minecraft", "hopper"), ContainerRule.builder()
                .containerSize(5)
                .layoutType(ContainerLayoutType.LINEAR)
                .validHostSlots(Arrays.asList(1, 2, 3))  // 只允许中间位置
                .directionMapping(Direction2D.LEFT, -1)
                .directionMapping(Direction2D.RIGHT, 1)
                .directionMapping(Direction2D.UP, -1)     // 不支持上下
                .directionMapping(Direction2D.DOWN, -1)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            // Iron Chests模组（示例）
            register(ResourceLocation.fromNamespaceAndPath("ironchests", "iron_chest"), ContainerRule.builder()
                .containerSize(45)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 44))
                .directionMapping(Direction2D.LEFT, -1)
                .directionMapping(Direction2D.RIGHT, 1)
                .directionMapping(Direction2D.UP, -9)
                .directionMapping(Direction2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );
            
            LOGGER.info("Initialized {} default container compatibility rules", RULES.size());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize default container rules", e);
        }
        
        // 可以继续添加更多模组...
    }

    /**
     * 注册新的容器规则
     */
    public static void register(ResourceLocation containerId, ContainerRule rule) {
        RULES.put(containerId, rule);
    }

    /**
     * 查找容器的规则
     */
    public static Optional<ContainerRule> findRule(ResourceLocation containerId) {
        return Optional.ofNullable(RULES.get(containerId));
    }

    /**
     * 获取所有已注册的规则（用于调试）
     */
    public static Set<Map.Entry<ResourceLocation, ContainerRule>> getAllRules() {
        return Collections.unmodifiableSet(RULES.entrySet());
    }

    /**
     * 从JSON加载配置（支持热重载）
     */
    public static void loadFromJson(String jsonPath) {
        // TODO: 实现JSON解析逻辑
        // 支持用户自定义配置文件
    }

    private static List<Integer> range(int start, int end) {
        List<Integer> list = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            list.add(i);
        }
        return list;
    }

    public enum ContainerLayoutType {
        RECTANGULAR_STANDARD,  // 标准矩形（如箱子、大箱子）
        RECTANGULAR_CUSTOM,    // 自定义矩形（如投掷器）
        LINEAR,                // 线性排列（如漏斗）
        IRREGULAR              // 不规则布局（如抽屉、自定义GUI）
    }

    public enum EdgeBehavior {
        INVALIDATE,   // 边缘位置返回-1（无效）
        WRAP,         // 环绕到另一侧
        CLAMP,        // 钳制到有效范围
        SKIP          // 跳过该方向
    }

    public record ContainerRule(
        int containerSize,
        ContainerLayoutType layoutType,
        List<Integer> validHostSlots,
        Map<Direction2D, Integer> directionMappings,
        EdgeBehavior edgeBehavior,
        boolean crossBlockEntitySupport,
        String description
    ) {
        public static Builder builder() {
            return new Builder();
        }
        
        public boolean isValidHostSlot(int slot) {
            return validHostSlots.contains(slot);
        }
        
        public int getDirectionOffset(Direction2D direction) {
            return directionMappings.getOrDefault(direction, 0);
        }
    }

    public static class Builder {
        private int containerSize = 0;
        private ContainerLayoutType layoutType = ContainerLayoutType.RECTANGULAR_STANDARD;
        private List<Integer> validHostSlots = new ArrayList<>();
        private Map<Direction2D, Integer> directionMappings = new EnumMap<>(Direction2D.class);
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

        public Builder validHostSlots(List<Integer> slots) {
            this.validHostSlots = slots;
            return this;
        }

        public Builder directionMapping(Direction2D direction, int offset) {
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

        public ContainerRule build() {
            if (containerSize <= 0) {
                throw new IllegalStateException("Container size must be positive");
            }
            if (validHostSlots.isEmpty()) {
                validHostSlots = range(0, containerSize - 1);
            }
            return new ContainerRule(
                containerSize, layoutType, validHostSlots, 
                directionMappings, edgeBehavior, crossBlockEntitySupport, description
            );
        }
    }
}