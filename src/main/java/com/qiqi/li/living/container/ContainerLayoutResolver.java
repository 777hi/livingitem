package com.qiqi.li.living.container;

import javax.annotation.Nullable;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

/** 共同布局选择顺序：实例来源、方块规则、方块学习、内建约定、启发式。 */
public final class ContainerLayoutResolver {
    private ContainerLayoutResolver() {}

    public static ContainerLayout resolve(TickableContainerContext ctx, @Nullable ContainerLayout previous) {
        int columns = ctx.getLiveColumns();
        if (columns > 0) return result(columns, ContainerLayout.Source.LIVE, previous);
        if (ctx.getEntity() instanceof AbstractHorse horse) {
            columns = horse.getInventoryColumns();
            if (columns > 0) return result(columns, ContainerLayout.Source.LIVE, previous);
        }
        for (var be : ctx.getAssociatedBlockEntities()) {
            columns = ItemFormContainers.columnsOfBlockEntity(be);
            if (columns > 0) return result(columns, ContainerLayout.Source.LIVE, previous);
        }

        int size = ctx.getSize();
        for (var be : ctx.getAssociatedBlockEntities()) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (id == null) continue;
            var rule = ContainerCompatibilityConfig.findRule(id, size);
            if (rule.isPresent()) return result(rule.get().columns(), ContainerLayout.Source.RULE, previous);
        }
        for (var be : ctx.getAssociatedBlockEntities()) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (id == null) continue;
            columns = LearnedLayouts.columnsOf(id, size);
            if (columns > 0) return result(columns, ContainerLayout.Source.LEARNED, previous);
        }

        ContainerLayout builtIn = BuiltInContainerLayouts.find(ctx);
        if (builtIn != null) return builtIn;
        return result(ContainerCompatibilityConfig.resolveColumns(size), ContainerLayout.Source.HEURISTIC, previous);
    }

    private static ContainerLayout result(int columns, ContainerLayout.Source source,
                                          @Nullable ContainerLayout previous) {
        if (previous != null && previous.columns() == columns && previous.source() == source) return previous;
        return new ContainerLayout(columns, source);
    }
}
