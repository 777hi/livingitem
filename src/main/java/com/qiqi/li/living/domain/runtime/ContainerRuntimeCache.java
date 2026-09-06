package com.qiqi.li.living.domain.runtime;

import com.qiqi.li.network.LivingItemSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端活物品运行时缓存 —— 存储每容器每槽位的运行时数据（遥测/瞬态字段）。
 *
 * <p>此类数据不写入 DataComponent，因此不会影响物品堆叠。数据通过
 * {@link LivingItemSyncPacket} 下发到客户端。</p>
 *
 * <h3>脏标记与同步</h3>
 * <p>每次更新数据时标记对应容器为"脏"。在 {@link #flushToClients(Level, Collection)}
 * 中遍历脏容器，向所有打开了该容器的玩家发送同步包。</p>
 */
public class ContainerRuntimeCache {

    /** 玩家背包容器键前缀（buildContainerKey 的 inventory 分支） */
    public static final String PLAYER_KEY_PREFIX = "player_";

    private static final Map<String, Map<Integer, LivingItemRuntimeData>> store = new ConcurrentHashMap<>();
    private static final Set<String> dirtyContainers = ConcurrentHashMap.newKeySet();

    // ── 更新 ────────────────────────────────────────────────

    public static void update(String containerKey, int slot, LivingItemRuntimeData data) {
        store.computeIfAbsent(containerKey, k -> new ConcurrentHashMap<>()).put(slot, data);
        dirtyContainers.add(containerKey);
    }

    public static void removeSlot(String containerKey, int slot) {
        Map<Integer, LivingItemRuntimeData> slots = store.get(containerKey);
        if (slots != null) {
            slots.remove(slot);
            dirtyContainers.add(containerKey);
        }
    }

    public static void removeContainer(String containerKey) {
        store.remove(containerKey);
        dirtyContainers.remove(containerKey);
    }

    // ── 读取 ────────────────────────────────────────────────

    public static LivingItemRuntimeData get(String containerKey, int slot) {
        Map<Integer, LivingItemRuntimeData> slots = store.get(containerKey);
        if (slots == null) return LivingItemRuntimeData.EMPTY;
        return slots.getOrDefault(slot, LivingItemRuntimeData.EMPTY);
    }

    public static Map<Integer, LivingItemRuntimeData> getSnapshot(String containerKey) {
        Map<Integer, LivingItemRuntimeData> slots = store.get(containerKey);
        if (slots == null) return Map.of();
        return Map.copyOf(slots);
    }

    // ── 同步 ────────────────────────────────────────────────

    /**
     * 将所有脏容器的运行时数据同步到已打开该容器的玩家。
     *
     * <p>应在每 tick 的容器处理完成后调用一次。</p>
     *
     * @param level  用于获取服务器玩家列表的世界
     * @param containerInstances 该容器对应的 {@link Container} 实例集合，用于匹配玩家菜单
     */
    public static void flushToClients(Level level, Collection<Container> containerInstances) {
        if (level == null || level.isClientSide) return;
        if (dirtyContainers.isEmpty()) return;

        Set<String> flushed = new HashSet<>(dirtyContainers);
        dirtyContainers.clear();

        for (String containerKey : flushed) {
            Map<Integer, LivingItemRuntimeData> snapshot = getSnapshot(containerKey);
            if (snapshot.isEmpty()) continue;

            var packet = new LivingItemSyncPacket(containerKey, snapshot);

            // 玩家背包（key = "player_" + UUID）：遥测直发背包主人本人。
            // 背包无 BE 实例可匹配（旧逻辑 containerInstances 为空 → 永远不发 →
            // 背包 tooltip 全 0），且背包菜单就是 inventoryMenu 本身，无法走菜单匹配。
            // 直发本人也避免与 BE 容器缓存在客户端互相覆盖。
            if (containerKey.startsWith(PLAYER_KEY_PREFIX)) {
                try {
                    var uuid = java.util.UUID.fromString(
                        containerKey.substring(PLAYER_KEY_PREFIX.length()));
                    var owner = level.getServer().getPlayerList().getPlayer(uuid);
                    if (owner != null) owner.connection.send(packet);
                } catch (IllegalArgumentException ignored) {
                    // key 非 UUID 形态（防御），跳过
                }
                continue;
            }

            // BE 容器：遍历所有在线玩家，找到打开了该容器的玩家
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (isViewingContainer(player, containerInstances)) {
                    player.connection.send(packet);
                }
            }
        }
    }

    /**
     * 向指定玩家直接发送指定容器的运行时数据（用于初始打开容器时）。
     */
    public static void sendSnapshotToPlayer(String containerKey, ServerPlayer player,
                                             Collection<Container> containerInstances) {
        Map<Integer, LivingItemRuntimeData> snapshot = getSnapshot(containerKey);
        if (snapshot.isEmpty()) return;

        var packet = new LivingItemSyncPacket(containerKey, snapshot);
        player.connection.send(packet);
    }

    /**
     * 判断玩家菜单里是否包含「该容器关联的 Container 实例」。
     *
     * <p><b>大箱子（v19.1 修复）</b>：原版大箱菜单的槽位容器是
     * {@link net.minecraft.world.CompoundContainer CompoundContainer}(左半 BE, 右半 BE)，
     * **不是 BE 本身**——旧逻辑 {@code containerInstances.contains(slot.container)}
     * 对大箱永远不匹配 → 同步包从不发给打开大箱的玩家 → 客户端遥测缓存为空 →
     * tooltip 永远显示 0/无数据（单箱菜单容器就是 BE 本体，故正常）。
     * 用 CompoundContainer 自带的 {@code contains(Container)} 匹配关联 BE。</p>
     */
    private static boolean isViewingContainer(ServerPlayer player, Collection<Container> containerInstances) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) return false;
        for (Slot slot : menu.slots) {
            Container menuContainer = slot.container;
            if (containerInstances.contains(menuContainer)) return true;
            if (menuContainer instanceof net.minecraft.world.CompoundContainer compound) {
                for (Container be : containerInstances) {
                    if (compound.contains(be)) return true;
                }
            }
        }
        return false;
    }
}