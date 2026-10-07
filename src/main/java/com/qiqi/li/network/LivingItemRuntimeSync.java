package com.qiqi.li.network;

import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 活物品运行时数据的<b>发包端</b>（L4）—— 档 2 反转发包职责的落点。
 *
 * <h3>为什么发包逻辑在这里，而不是在 {@link ContainerRuntimeCache}</h3>
 * <p>缓存住在 L2（{@code living/runtime}），网络包住在 L4（{@code network}）。
 * 若缓存自己 {@code new LivingItemSyncPacket(...)} 并 send ⇒ <b>L2 依赖 L4 = R1 违规</b>。
 * ⇒ 缓存只负责「攒脏 + 交出快照」（{@link ContainerRuntimeCache#drainDirty()}），
 * 由本类（L4）决定「发给谁、怎么发」。</p>
 *
 * <h3>两个分支的过滤强度不同（⚠️ 别统一）</h3>
 * <table>
 *   <tr><th>分支</th><th>脏过滤</th><th>viewer 过滤</th></tr>
 *   <tr><td>BE 容器</td><td>✅</td><td>✅ {@code isViewing}</td></tr>
 *   <tr><td>玩家背包（{@code player_<uuid>}）</td><td>✅</td>
 *       <td>⚠️ <b>用「有没有开着容器界面」判</b>（不可是 {@code isViewing}）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>背包分支不可复用 {@code isViewing}</b>：它对背包恒 {@code false}
 * （{@code ContainerContexts} 里 {@code menu == inventoryMenu} 早退）——
 * 那正是 v19.1「背包 tooltip 全 0」bug 的成因。也<b>不可</b>判「是不是 InventoryScreen」：
 * 背包 GUI 里的 tooltip 同样要显示。</p>
 */
public final class LivingItemRuntimeSync {

    private LivingItemRuntimeSync() {}

    /**
     * 取出本 tick 攒下的脏容器快照并派发（由 L4 的 tick 调用）。
     *
     * @param players            在线玩家
     * @param containerLookup    容器键 → 该容器关联的 {@link Container} 实例集合
     *                           （用于 BE 容器的 viewer 匹配；背包键不需要）
     */
    public static void flush(Collection<ServerPlayer> players,
                             java.util.function.Function<String, Collection<Container>> containerLookup) {
        Map<String, Map<Integer, RuntimeSegments>> dirty = ContainerRuntimeCache.drainDirty();
        if (dirty.isEmpty()) return;

        for (var entry : dirty.entrySet()) {
            String containerKey = entry.getKey();
            var packet = new LivingItemSyncPacket(containerKey, entry.getValue());

            if (containerKey.startsWith(ContainerRuntimeCache.PLAYER_KEY_PREFIX)) {
                sendToOwner(players, containerKey, packet);
            } else {
                Collection<Container> instances = containerLookup.apply(containerKey);
                if (instances == null || instances.isEmpty()) continue;
                List<ServerPlayer> viewers = ContainerRuntimeCache.findViewers(players, instances);
                for (ServerPlayer viewer : viewers) {
                    viewer.connection.send(packet);
                }
            }
        }
    }

    /**
     * 玩家背包：直发**背包主人本人**。
     *
     * <p>背包无 BE 实例可匹配（旧逻辑 containerInstances 为空 → 永远不发 →
     * 背包 tooltip 全 0），且背包菜单就是 inventoryMenu 本身，无法走菜单匹配。
     * 直发本人也避免与 BE 容器缓存在客户端互相覆盖。</p>
     *
     * <p>⚠️ 再加一道「有没有开着容器界面」守卫（2026-10-08 可选优化）：玩家在普通游戏
     * 画面里根本不会读这个包（tooltip 只在 {@code AbstractContainerScreen} 里渲染），
     * 而背包每 tick 都被 tick ⇒ 否则每 tick 一个废包。</p>
     */
    private static void sendToOwner(Collection<ServerPlayer> players, String containerKey,
                                    LivingItemSyncPacket packet) {
        UUID uuid;
        try {
            uuid = UUID.fromString(containerKey.substring(
                ContainerRuntimeCache.PLAYER_KEY_PREFIX.length()));
        } catch (IllegalArgumentException ignored) {
            return;   // key 非 UUID 形态（防御），跳过
        }
        for (ServerPlayer player : players) {
            if (!player.getUUID().equals(uuid)) continue;
            if (ContainerRuntimeCache.isJustInventoryMenu(player)) return;   // 没开容器界面 ⇒ 无人读
            player.connection.send(packet);
            return;
        }
    }

    /**
     * 向指定玩家直接发送某容器的运行时快照（初始打开容器时的补发入口）。
     *
     * <p>当前<b>无调用者</b>：保留给「打开背包/容器立即显示」的即时性补发。</p>
     */
    public static void sendSnapshotToPlayer(String containerKey, ServerPlayer player) {
        Map<Integer, RuntimeSegments> snapshot = ContainerRuntimeCache.getSnapshot(containerKey);
        if (snapshot.isEmpty()) return;
        player.connection.send(new LivingItemSyncPacket(containerKey, snapshot));
    }
}
