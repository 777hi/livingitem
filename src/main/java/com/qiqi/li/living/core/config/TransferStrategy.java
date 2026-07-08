package com.qiqi.li.living.core.config;

import java.util.List;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.SlotMapping;

public interface TransferStrategy {

    List<TransferPath> resolvePaths(int hostSlot, int containerSize);

    String getType();

    String getDisplayName();

    record TransferPath(Pos2D source, Pos2D target) {}

    static TransferStrategy single(SlotMapping mapping) {
        return new SingleDirectionStrategy(mapping);
    }

    static TransferStrategy distribute(Pos2D source, List<Pos2D> targets) {
        return new DistributeStrategy(source, targets);
    }

    static TransferStrategy collect(List<Pos2D> sources, Pos2D target) {
        return new CollectStrategy(sources, target);
    }
}

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