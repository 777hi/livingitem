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

            // 遍历所有在线玩家，找到打开了该容器的玩家
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

    private static boolean isViewingContainer(ServerPlayer player, Collection<Container> containerInstances) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) return false;
        for (Slot slot : menu.slots) {
            if (containerInstances.contains(slot.container)) {
                return true;
            }
        }
        return false;
    }
}