package com.qiqi.li.living.transfer;

import com.qiqi.li.living.model.Pos2D;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 容器兼容性配置 —— 为不同容器类型定义槽位解析规则。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>注册每种容器类型的解析规则（ContainerRule）</li>
 *   <li>根据容器 ID 查找对应的规则</li>
 * </ol>
 *
 * <p>规则来源：</p>
 * <ul>
 *   <li>模组自带规则随 jar 打包发布，位于 {@code assets/living_item/container_rules.json}</li>
 *   <li>玩家通过指令 {@code /livingitem container register} 注册的规则存储在配置目录</li>
 * </ul>
 *
 * <p>规则匹配流程：</p>
 * <pre>
 *   identifyContainer() → ResourceLocation → findRule() → ContainerRule
 * </pre>
 */
public final class ContainerCompatibilityConfig {

    /** 容器规则注册表（容器 ID → 规则） */
    private static final Map<ResourceLocation, ContainerRule> RULES = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCompatibilityConfig.class);

    private ContainerCompatibilityConfig() {}

    /** 注册容器规则 */
    public static void register(ResourceLocation containerId, ContainerRule rule) {
        RULES.put(containerId, rule);
    }

    /** 移除容器规则 */
    public static boolean remove(ResourceLocation containerId) {
        return RULES.remove(containerId) != null;
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
     * 根据槽位数和容器对象推断列数。
     * <p>
     * 三层策略：
     * <ol>
     *   <li><b>已知容器类型</b> — 用 instanceof 判断原版容器，直接返回确定列数</li>
     *   <li><b>加权启发式</b> — 按真实模组容器分布排序候选宽度，优先常见宽度</li>
     * </ol>
     *
     * @param slotCount 容器槽位数
     * @param container 容器对象（nullable），用于类型推断
     * @return 推断的列数
     */
    public static int resolveColumns(int slotCount, Container container) {
        // Tier 2: 已知容器类型直接推断
        if (container != null) {
            int typeColumns = resolveColumnsFromContainer(container);
            if (typeColumns > 0) return typeColumns;
        }
        // Tier 3: 加权启发式
        return resolveColumns(slotCount);
    }

    /**
     * 根据原版容器类型推断列数。
     *
     * @return 列数，未知类型返回 -1
     */
    private static int resolveColumnsFromContainer(Container container) {
        if (container instanceof ChestBlockEntity) return 9;
        if (container instanceof HopperBlockEntity) return 1;
        if (container instanceof DispenserBlockEntity || container instanceof DropperBlockEntity) return 3;
        if (container instanceof FurnaceBlockEntity ||
            container instanceof BlastFurnaceBlockEntity ||
            container instanceof SmokerBlockEntity) return 1;
        if (container instanceof BrewingStandBlockEntity) return 1;
        if (container instanceof ShulkerBoxBlockEntity) return 9;
        if (container instanceof BarrelBlockEntity) return 9;
        return -1; // 未知类型
    }

    /** 加权启发式列数推断。</para>
     * <p>
     * 按真实模组容器分布排序候选宽度，优先常见宽度：
     * 9（原版标准）→ 10 → 12 → 13 → 8 → 7 → 6 → 11 → 5 → 4 → 3 → 2 → 1。
     * </summary> */
    public static int resolveColumns(int slotCount) {
        int[] commonWidths = {9, 10, 12, 13, 8, 7, 6, 11, 5, 4, 3, 2, 1};
        for (int w : commonWidths) {
            if (slotCount >= w && slotCount % w == 0) return w;
        }
        return 1;
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

    /**
     * 清空所有已注册规则。
     *
     * <p>仅供 {@code ContainerRuleConfig.load()} 重建状态与单元测试使用。
     * 生产代码调用会丢失内存中的全部规则（需重新 {@code load()} 恢复）。</p>
     */
    static void clearAllRules() {
        RULES.clear();
    }

    /** 从 JSON 配置文件加载规则 */
    public static void loadFromConfig() {
        ContainerRuleConfig.load();
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