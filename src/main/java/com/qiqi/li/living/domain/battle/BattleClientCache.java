package com.qiqi.li.living.domain.battle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.util.StaticCacheRegistry;

/**
 * 客户端战局缓存 —— 当前菜单的敌军快照 + 槽位映射 + 待重演事件。
 *
 * <p>menuId 门控（Portal 同款）：包不是给当前打开菜单的直接丢弃；换菜单清空。
 * 本类放领域包内、不 import 客户端类（双端安全，BeaconHostClientCache 同款）。</p>
 */
public final class BattleClientCache {

    /** 敌军最新快照位（目标值；显示位置由 Overlay 侧插值逼近）。 */
    public record EnemyPos(int lane, float progress, boolean helmet) {}

    private static int menuId = -1;
    private static int lanes;
    private static int depth;
    private static final Map<UUID, EnemyPos> ENEMIES = new HashMap<>();
    /** 菜单槽位 → 逻辑槽位（事件 slot → GUI 格定位）。 */
    private static final Map<Integer, Integer> MENU_SLOTS = new HashMap<>();
    private static final ArrayDeque<BattleEvent> EVENTS = new ArrayDeque<>();

    static {
        StaticCacheRegistry.onClientLogout(BattleClientCache::clear);
    }

    private BattleClientCache() {}

    public static void update(int currentMenuId, BattleSnapshotPacket packet) {
        if (packet.menuId() != currentMenuId) return;
        if (menuId != currentMenuId) clear();
        menuId = currentMenuId;
        lanes = packet.lanes();
        depth = packet.depth();
        ENEMIES.clear();
        for (var enemy : packet.enemies()) {
            ENEMIES.put(BattleSnapshotPacket.enemyId(enemy),
                new EnemyPos(enemy.lane(), enemy.progress(), enemy.helmet()));
        }
        MENU_SLOTS.clear();
        MENU_SLOTS.putAll(packet.menuSlots());
        EVENTS.addAll(packet.events());
    }

    public static boolean active(int currentMenuId) {
        return menuId == currentMenuId && !ENEMIES.isEmpty();
    }

    public static Map<UUID, EnemyPos> enemies() {
        return ENEMIES;
    }

    /** 逻辑槽位 → 菜单槽位；无映射返回 -1。 */
    public static int menuSlotOf(int logicalSlot) {
        return MENU_SLOTS.getOrDefault(logicalSlot, -1);
    }

    /** 菜单槽位 → 逻辑槽位（棋盘网格从这些槽位反推）。 */
    public static Map<Integer, Integer> menuSlots() {
        return MENU_SLOTS;
    }

    /** 取走全部待重演事件。 */
    public static List<BattleEvent> consumeEvents() {
        if (EVENTS.isEmpty()) return List.of();
        List<BattleEvent> out = new ArrayList<>(EVENTS);
        EVENTS.clear();
        return out;
    }

    public static void clear() {
        menuId = -1;
        lanes = 0;
        depth = 0;
        ENEMIES.clear();
        MENU_SLOTS.clear();
        EVENTS.clear();
    }
}
