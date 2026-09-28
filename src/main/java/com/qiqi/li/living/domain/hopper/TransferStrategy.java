package com.qiqi.li.living.domain.hopper;

import java.util.List;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.SlotMapping;

/**
 * 传输策略接口 —— 定义活物品的物品传输路径。
 *
 * 传输策略决定了活物品从哪些方向取物品、往哪些方向放物品。
 * 支持三种基本策略：
 *
 * 1. 单向传输（single）：从一个方向取，往一个方向放
 *    示例：活漏斗的 "上传下"（从上方取，放到下方）
 *
 * 2. 分发传输（distribute）：从一个方向取，往多个方向放
 *    示例：从上方取物品，同时放到左右两侧
 *
 * 3. 汇聚传输（collect）：从多个方向取，往一个方向放
 *    示例：从左右两侧取物品，统一放到下方
 *
 * 使用工厂方法创建策略实例：
 * <pre>
 * TransferStrategy.single(SlotMapping.UP_TO_DOWN);          // 单向
 * TransferStrategy.distribute(Pos2D.UP, List.of(LEFT, RIGHT)); // 分发
 * TransferStrategy.collect(List.of(LEFT, RIGHT), Pos2D.DOWN);  // 汇聚
 * </pre>
 */
public interface TransferStrategy {

    /**
     * 解析传输路径列表。
     *
     * @param hostSlot 活物品所在槽位
     * @param containerSize 容器大小（用于边界检查）
     * @return 传输路径列表
     */
    List<TransferPath> resolvePaths(int hostSlot, int containerSize);

    /** 获取策略类型标识（如 "single"、"distribute"、"collect"） */
    String getType();

    /** 获取策略的显示名称（用于 Tooltip） */
    String getDisplayName();

    /**
     * 传输路径 —— 定义一条从源到目标的传输方向。
     *
     * @param source 取物品的方向偏移
     * @param target 放物品的方向偏移
     */
    record TransferPath(Pos2D source, Pos2D target) {}

    /** 创建单向传输策略 */
    static TransferStrategy single(SlotMapping mapping) {
        return new SingleDirectionStrategy(mapping);
    }

    /** 创建分发传输策略（一源多目标） */
    static TransferStrategy distribute(Pos2D source, List<Pos2D> targets) {
        return new DistributeStrategy(source, targets);
    }

    /** 创建汇聚传输策略（多源一目标） */
    static TransferStrategy collect(List<Pos2D> sources, Pos2D target) {
        return new CollectStrategy(sources, target);
    }
}

/**
 * 单向传输策略 —— 从一个方向取物品，往一个方向放物品。
 *
 * 最基本的传输策略，用于活漏斗等单方向传输场景。
 * 示例：上传下（UP→DOWN）、左传右（LEFT→RIGHT）
 */
class SingleDirectionStrategy implements TransferStrategy {

    private final SlotMapping mapping;

    SingleDirectionStrategy(SlotMapping mapping) {
        this.mapping = mapping;
    }

    @Override
    public List<TransferPath> resolvePaths(int hostSlot, int containerSize) {
        return List.of(new TransferPath(mapping.sourceOffset(), mapping.targetOffset()));
    }

    @Override
    public String getType() { return "single"; }

    @Override
    public String getDisplayName() { return mapping.displayName(); }
}

/**
 * 分发传输策略 —— 从一个方向取物品，往多个方向同时放物品。
 *
 * 用于需要将物品分发到多个目标的场景。
 * 示例：从上方取物品，同时放到左右两侧
 */
class DistributeStrategy implements TransferStrategy {

    private final Pos2D source;
    private final List<Pos2D> targets;

    DistributeStrategy(Pos2D source, List<Pos2D> targets) {
        this.source = source;
        this.targets = List.copyOf(targets);
    }

    @Override
    public List<TransferPath> resolvePaths(int hostSlot, int containerSize) {
        return targets.stream()
            .map(target -> new TransferPath(source, target))
            .toList();
    }

    @Override
    public String getType() { return "distribute"; }

    @Override
    public String getDisplayName() {
        return String.format("分发: %s → [%s]",
            source.getSymbol(),
            targets.stream().map(Pos2D::getSymbol).reduce((a, b) -> a + ", " + b).orElse(""));
    }
}

/**
 * 汇聚传输策略 —— 从多个方向取物品，统一放到一个方向。
 *
 * 用于需要从多个来源收集物品的场景。
 * 示例：从左右两侧取物品，统一放到下方
 */
class CollectStrategy implements TransferStrategy {

    private final List<Pos2D> sources;
    private final Pos2D target;

    CollectStrategy(List<Pos2D> sources, Pos2D target) {
        this.sources = List.copyOf(sources);
        this.target = target;
    }

    @Override
    public List<TransferPath> resolvePaths(int hostSlot, int containerSize) {
        return sources.stream()
            .map(source -> new TransferPath(source, target))
            .toList();
    }

    @Override
    public String getType() { return "collect"; }

    @Override
    public String getDisplayName() {
        return String.format("汇聚: [%s] → %s",
            sources.stream().map(Pos2D::getSymbol).reduce((a, b) -> a + ", " + b).orElse(""),
            target.getSymbol());
    }
}