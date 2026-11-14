package com.qiqi.li.living.domain.portal;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.qiqi.li.living.util.StaticCacheRegistry;

/** 当前菜单的权威门面与命中映射；不同真实容器分别保存。 */
public final class PortalClientCache {
    public record Snapshot(int width, int size, Set<Integer> cells, Map<Integer, Integer> menuSlots) {
        public Snapshot {
            cells = Set.copyOf(cells);
            menuSlots = Map.copyOf(menuSlots);
        }
    }

    private static int menuId = -1;
    private static final Map<String, Snapshot> SNAPSHOTS = new HashMap<>();

    static {
        StaticCacheRegistry.onClientLogout(PortalClientCache::clear);
    }

    private PortalClientCache() {}

    public static void update(int currentMenuId, PortalSnapshotPacket packet) {
        if (packet.menuId() != currentMenuId) return;
        if (menuId != currentMenuId) clear();
        menuId = currentMenuId;
        if (packet.cells().isEmpty()) SNAPSHOTS.remove(packet.containerKey());
        else SNAPSHOTS.put(packet.containerKey(), new Snapshot(
            packet.width(), packet.size(), packet.cells(), packet.menuSlots()));
    }

    public static boolean contains(int currentMenuId, int menuSlot) {
        if (menuId != currentMenuId) return false;
        for (Snapshot snapshot : SNAPSHOTS.values()) {
            Integer logical = snapshot.menuSlots().get(menuSlot);
            if (logical != null && snapshot.cells().contains(logical)) return true;
        }
        return false;
    }

    public static List<Snapshot> snapshots(int currentMenuId) {
        return menuId == currentMenuId ? List.copyOf(SNAPSHOTS.values()) : List.of();
    }

    public static void clear() {
        SNAPSHOTS.clear();
        menuId = -1;
    }
}
