package com.qiqi.li.client.render.tlm;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 唯一相邻迁移匹配；相同快照不是实例身份，歧义不迁移。 */
final class SoulSlabPreviewMoves {
    record Position(Object owner, int index, int x, int y) {
        @Override public boolean equals(Object other) {
            return other instanceof Position p && owner == p.owner && index == p.index;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(owner) + index; }
    }
    record Snapshot(Position position, ItemStack stack) {}

    private SoulSlabPreviewMoves() {}

    static ItemStack stable(ItemStack stack) {
        ItemStack copy = stack.copy();
        var clock = BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("living_item", "living_soul_slab_clock"));
        if (clock != null) copy.remove(clock);
        return copy;
    }

    static Map<Position, Position> match(List<Snapshot> removed, List<Snapshot> added) {
        Map<Position, List<Position>> candidates = new HashMap<>();
        Map<Position, Integer> incoming = new HashMap<>();
        for (Snapshot source : removed) {
            List<Position> destinations = new ArrayList<>();
            for (Snapshot target : added) {
                if (adjacent(source.position, target.position) && ItemStack.matches(source.stack, target.stack)) {
                    destinations.add(target.position);
                    incoming.merge(target.position, 1, Integer::sum);
                }
            }
            candidates.put(source.position, destinations);
        }
        Map<Position, Position> moves = new HashMap<>();
        candidates.forEach((source, targets) -> {
            if (targets.size() == 1 && incoming.get(targets.getFirst()) == 1) {
                moves.put(targets.getFirst(), source);
            }
        });
        return moves;
    }

    private static boolean adjacent(Position a, Position b) {
        if (a.owner != b.owner || a.index == b.index) return false;
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        return (dy == 0 && dx >= 16 && dx <= 24) || (dx == 0 && dy >= 16 && dy <= 24);
    }
}
