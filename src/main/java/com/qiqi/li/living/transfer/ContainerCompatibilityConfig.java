package com.qiqi.li.living.transfer;

import com.qiqi.li.living.model.Pos2D;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import com.qiqi.li.living.container.BuiltInContainerLayouts;
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

    /**
     * 容器规则注册表（容器 ID → 该 ID 的**全部容量档位**）。
     *
     * <p>⚠️ 为什么是 {@code List} 而不是单条（2026-10-18，专题 S4）：同一个方块实体类型
     * 可以有多档容量，而<b>列数不同</b>。典型如：27 / 45 / 54 / 81
     * 都是 9 列，但 108 / 120 是 <b>12 列</b>。旧实现 {@code Map<id, rule>}
     * 一个 id 只能存一条 ⇒ 注册了 108 就把 120 挤掉，反之亦然。</p>
     *
     * <p>列数算错的后果不是「显示难看」而是<b>功能失效</b>：活熔炉的燃料槽在
     * {@code slot + width}，宽度错 9 vs 12 ⇒ 永远读不到燃料 ⇒ 不烧。
     * 活漏斗同样按方向偏移（默认 {@code UP → DOWN}，即 {@code slot ∓ width}），也会搬错格子——
     * 但它<b>仍在搬</b>，所以「看起来正常」；熔炉读不到燃料则「明确不烧」。
     * 同一个偏差，一个隐形、一个可见，所以后者才是最先被发现的。</p>
     */
    private static final Map<ResourceLocation, List<ContainerRule>> RULES = new HashMap<>();

    /**
     * <b>破坏后仍保有物品内容</b>的容器 ID（2026-10-20）。
     *
     * <p>只对它们做「流体数据跟着掉落物走」的迁移。理由是<b>语义一致</b>：</p>
     * <ul>
     *   <li><b>在集合里</b>（潜影盒等）：撸掉后内容还在物品里 ⇒
     *       流体跟着走，重新放置后「内容 + 水」都还在；</li>
     *   <li><b>不在集合里</b>（原版箱子 / 铁箱子 / 炼金箱子…）：撸掉后内容本来就消失了 ⇒
     *       流体也跟着方块一起消失。<b>否则会得到「空箱子但有水」这种半保留状态</b>——
     *       玩家看到的是「箱子空了，水却还在」，比丢水更让人困惑。</li>
     * </ul>
     *
     * <p>⚠️ 为什么是<b>显式数据</b>而不是自动判断：没有任何通用 API 能回答「这个容器的内容
     * 会不会跟着掉落物走」——潜影盒用 {@code minecraft:container} 组件、有的用 SavedData
     * 按 contents UUID、有的用 {@code CLUSTER_CONTAINER} 隐式组件，各走各的路。
     * 自动判断只能逐个认组件，那就是 `D-container-09` 禁止的「猜第三方形状」。
     * ⇒ 做成规则数据的一部分，由内置资源或玩家声明。</p>
     */
    private static final Set<ResourceLocation> CONTENT_RETAINING = new HashSet<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCompatibilityConfig.class);

    private ContainerCompatibilityConfig() {}

    /**
     * 注册容器规则。
     *
     * <p>同一 {@code (id, containerSize)} 已存在则<b>覆盖</b>（玩家修正内置数据是预期行为），
     * 否则追加为新的容量档位。</p>
     */
    public static void register(ResourceLocation containerId, ContainerRule rule) {
        List<ContainerRule> list = RULES.computeIfAbsent(containerId, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).containerSize() == rule.containerSize()) {
                list.set(i, rule);
                return;
            }
        }
        list.add(rule);
    }

    /** 移除该 ID 的<b>全部</b>容量档位。 */
    public static boolean remove(ResourceLocation containerId) {
        return RULES.remove(containerId) != null;
    }

    /**
     * 只移除该 ID 的<b>某一档</b>容量 —— 玩家差异的删除是按档记的
     * （见 {@code ContainerRuleConfig.REMOVED_KEYS}），加载时要能单独摘掉一档。
     *
     * @return 是否确实删掉了这一档
     */
    public static boolean removeTier(ResourceLocation containerId, int containerSize) {
        List<ContainerRule> list = RULES.get(containerId);
        if (list == null) return false;
        boolean removed = list.removeIf(r -> r.containerSize() == containerSize);
        if (list.isEmpty()) RULES.remove(containerId);
        return removed;
    }

    /**
     * 按 ID 查规则 —— 返回该 ID 的<b>第一条</b>（档位顺序无关）。
     *
     * <p>⚠️ 凡是要拿「这个容器的列数」的地方都<b>必须</b>用
     * {@link #findRule(ResourceLocation, int)}：单参数版本对多档容器会拿到别的档位。
     * 本重载只用于「这个 ID 有没有注册过」这类存在性判断与诊断展示。</p>
     */
    public static Optional<ContainerRule> findRule(ResourceLocation containerId) {
        List<ContainerRule> list = RULES.get(containerId);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 按 {@code (ID, 容量)} 精确查规则 —— 多档容器的正确入口。 */
    public static Optional<ContainerRule> findRule(ResourceLocation containerId, int containerSize) {
        List<ContainerRule> list = RULES.get(containerId);
        if (list == null) return Optional.empty();
        for (ContainerRule rule : list) {
            if (rule.containerSize() == containerSize) return Optional.of(rule);
        }
        return Optional.empty();
    }

    public static Optional<ContainerRule> findRuleBySize(int containerSize) {
        for (var entry : RULES.entrySet()) {
            for (ContainerRule rule : entry.getValue()) {
                if (rule.containerSize() == containerSize) {
                    return Optional.of(rule);
                }
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
     * 根据原版容器类型推断列数（Tier 2 兜底表）。
     *
     * <p>⚠️ <b>契约：与 {@code container_rules.json}（Tier 1）重叠的条目必须一致</b>——
     * 规则优先，表只在「规则未覆盖该容器」时生效。2026-10-18 审查实录：漏斗在本表写
     * <b>1</b>、规则表写 <b>5</b> ⇒ 实际生效 5（规则优先），但
     * {@code /livingitem container} 的诊断命令走本表显示 <b>1</b>（误导），
     * 且规则加载失败时会静默变回 1。现已对齐为 5，并由
     * {@code ContainerCompatibilityConfigTest#typeTable_agreesWithRegisteredRules} 守住。</p>
     *
     * <p>本表保留而非删除的原因：规则文件加载失败时它仍是原版容器的可靠兜底
     * （启发式对发射器 9 槽会算成 9，而正确值是 3）。</p>
     *
     * @return 列数，未知类型返回 -1
     */
    public static int resolveColumnsFromContainer(Container container) {
        return BuiltInContainerLayouts.columnsOf(container);
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

    /**
     * ⚠️ <b>已删除（2026-10-20）：按 namespace + 分词模糊匹配规则</b>。
     *
     * <p>原实现拿「规则 id 的任一词」去匹配「目标 BE id 的任一词」，同 namespace 即命中，
     * 并返回该规则的第一档。三个问题：</p>
     * <ul>
     *   <li><b>它在猜</b>——「这条规则大概适用于这个 BE」不是数据，是推测。与
     *       {@code D-container-09}「核心不做第三方形状猜测」同源冲突；</li>
     *   <li><b>多档时只取第一档</b>：同 id 有六档时，模糊命中永远拿到第一档，
     *       容量校验一挡就返回空 ⇒ 行为取决于「哪档先注册」，不确定；</li>
     *   <li><b>不可观测</b>：现有 27 条规则全是精确 id，删掉后所有已知容器的列数不变
     *       （未注册类型走 Tier 2 原版类型表 / Tier 3 启发式，语义明确）。</li>
     * </ul>
     *
     * <p>⇒ 规则查找只认<b>精确 id + 容量</b>。要支持新容器就登记一条规则，不要指望模糊命中。
     * 守卫：{@code SimpleContainerContextTest.unregisteredType_doesNotBorrowRuleFromSiblingId}。</p>
     */

    /**
     * 获取所有已注册规则（不可变视图，<b>已按容量档位摊平</b>）。
     *
     * <p>同一个 ID 有多个容量档位时会返回多条 —— 调用方按 {@code entry.getKey()} 分组即可。</p>
     */
    public static List<Map.Entry<ResourceLocation, ContainerRule>> getAllRules() {
        List<Map.Entry<ResourceLocation, ContainerRule>> flat = new ArrayList<>();
        for (var entry : RULES.entrySet()) {
            for (ContainerRule rule : entry.getValue()) {
                flat.add(Map.entry(entry.getKey(), rule));
            }
        }
        return Collections.unmodifiableList(flat);
    }

    /**
     * 清空所有已注册规则。
     *
     * <p>仅供 {@code ContainerRuleConfig.load()} 重建状态与单元测试使用。
     * 生产代码调用会丢失内存中的全部规则（需重新 {@code load()} 恢复）。</p>
     */
    static void clearAllRules() {
        RULES.clear();
        CONTENT_RETAINING.clear();
    }

    /**
     * 登记「破坏后仍保有物品内容」的容器（流体数据会跟着掉落物走）。
     *
     * @param containerId 方块实体类型 ID（不是方块 ID）
     */
    public static void registerContentRetaining(ResourceLocation containerId) {
        CONTENT_RETAINING.add(containerId);
    }

    /**
     * 该容器破坏后是否仍保有物品内容 ⇒ 是否迁移流体数据。
     *
     * <p><b>默认 {@code false}</b>：未登记的容器按「内容随方块消失」处理，
     * 流体也跟着消失——这与它的物品内容行为一致，不会留下「空箱子但有水」。</p>
     */
    public static boolean retainsContentsOnBreak(ResourceLocation containerId) {
        return CONTENT_RETAINING.contains(containerId);
    }

    /** 已登记的「保留内容」容器 ID（导出用，已排序）。 */
    public static List<ResourceLocation> getContentRetaining() {
        List<ResourceLocation> ids = new ArrayList<>(CONTENT_RETAINING);
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        return ids;
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