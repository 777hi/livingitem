package com.qiqi.li.client.render.tlm;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** 唯一预览迁移匹配；真实栈引用优先，相同快照歧义时不猜实例身份。 */
final class SoulSlabPreviewMoves {
    private static final int CURSOR_INDEX = Integer.MIN_VALUE;

    record Position(Object owner, int index, int x, int y) {
        static Position cursor(Object menu) {
            return new Position(menu, CURSOR_INDEX, 0, 0);
        }

        boolean isCursor() {
            return index == CURSOR_INDEX;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Position p && owner == p.owner && index == p.index;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(owner) + index; }
    }

    record Snapshot(Position position, ItemStack stack, ItemStack reference) {
        Snapshot(Position position, ItemStack stack) {
            this(position, stack, null);
        }
    }

    private SoulSlabPreviewMoves() {}

    static ItemStack stable(ItemStack stack) {
        ItemStack copy = stack.copy();
        remove(copy, "living_soul_slab_clock");
        remove(copy, "living_soul_slab_sitting");
        com.qiqi.li.living.compat.tlm.TlmSoulData.stripPendingFavor(copy);
        return copy;
    }

    private static void remove(ItemStack stack, String path) {
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("living_item", path));
        if (type != null) stack.remove(type);
    }

    static Map<Position, Position> match(List<Snapshot> removed, List<Snapshot> added) {
        Map<Position, Position> moves = new LinkedHashMap<>();
        Set<Position> usedSources = new HashSet<>();
        Set<Position> usedTargets = new HashSet<>();

        matchPhase(removed, added, usedSources, usedTargets, moves,
                (source, target) -> source.reference != null && source.reference == target.reference
                        && ItemStack.matches(source.stack, target.stack), false);
        matchPhase(removed, added, usedSources, usedTargets, moves,
                (source, target) -> ItemStack.matches(source.stack, target.stack), true);
        return moves;
    }

    private static void matchPhase(List<Snapshot> removed, List<Snapshot> added,
                                   Set<Position> usedSources, Set<Position> usedTargets,
                                   Map<Position, Position> moves,
                                   BiPredicate<Snapshot, Snapshot> equivalent,
                                   boolean restrictMovement) {
        Map<Position, List<Position>> candidates = new HashMap<>();
        Map<Position, Integer> incoming = new HashMap<>();
        for (Snapshot source : removed) {
            if (usedSources.contains(source.position)) continue;
            List<Position> destinations = new ArrayList<>();
            for (Snapshot target : added) {
                if (usedTargets.contains(target.position) || restrictMovement && !canMove(source.position, target.position)
                        || !equivalent.test(source, target)) continue;
                destinations.add(target.position);
                incoming.merge(target.position, 1, Integer::sum);
            }
            candidates.put(source.position, destinations);
        }

        Map<Position, Position> phase = new LinkedHashMap<>();
        candidates.forEach((source, targets) -> {
            if (targets.size() == 1 && incoming.getOrDefault(targets.getFirst(), 0) == 1) {
                phase.put(targets.getFirst(), source);
            }
        });
        phase.forEach((target, source) -> {
            usedSources.add(source);
            usedTargets.add(target);
            moves.put(target, source);
        });
    }

    private static boolean canMove(Position source, Position target) {
        return source.equals(target) || source.isCursor() || target.isCursor() || adjacent(source, target);
    }

    private static boolean adjacent(Position a, Position b) {
        if (a.owner != b.owner || a.index == b.index) return false;
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        return (dy == 0 && dx >= 16 && dx <= 24) || (dx == 0 && dy >= 16 && dy <= 24);
    }
}
