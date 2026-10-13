package com.qiqi.li.network;

import com.qiqi.li.living.container.ContainerKeys;
import com.qiqi.li.living.container.ContainerKind;
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
 * <h3>分派按「容器种类」而非「键前缀」（2026-10-13 修）</h3>
 * <p>种类由 {@link ContainerKeys#kindOf} 一处判定，分两路：</p>
 * <table>
 *   <tr><th>种类</th><th>发给谁</th></tr>
 *   <tr><td>{@code PLAYER_INV} / {@code ENDER_CHEST}（{@link ContainerKind#isOwnedByPlayer()}）</td>
 *       <td>按 UUID <b>直发主人本人</b>（这两类没有 BE 实例可做 viewer 匹配）</td></tr>
 *   <tr><td>其余（世界容器 / 掉落物）</td><td>按 {@code isViewing} 匹配 viewer</td></tr>
 * </table>
 *
 * <p>⚠️ <b>背包 / 末影箱不可复用 {@code isViewing}</b>：它对背包恒 {@code false}
 * （{@code ContainerContexts} 里 {@code menu == inventoryMenu} 早退）—— 那正是 v19.1
 * 「背包 tooltip 全 0」bug 的成因。</p>
 *
 * <h3>⭐ 2026-10-13 修复的两处（同源：键形反推）</h3>
 * <ol>
 *   <li><b>删掉「有没有开着容器界面」守卫</b>：它用 {@code menu == inventoryMenu} 判「没开界面」，
 *       但<b>玩家按 E 打开背包界面时服务端的 menu 仍是 inventoryMenu</b>（原版不换菜单）
 *       ⇒ 服务端根本区分不了「开着背包界面」与「什么都没开」⇒ 该守卫把前者的包也拦掉了
 *       ⇒ 背包里的活物品 tooltip 运行时行不显示。<b>删守卫</b>后与流体轨一致
 *       （{@code FluidFlowServerSync} 对背包就是直发主人、不判界面）。</li>
 *   <li><b>UUID 解析改用 {@link ContainerKeys#ownerOf}</b>：旧实现直接
 *       {@code UUID.fromString(key.substring("player_".length()))}，对末影箱键
 *       {@code player_<uuid>_ender_chest} 抛 {@code IllegalArgumentException} 被 catch 吞掉
 *       ⇒ <b>末影箱从不发包</b>（其 tooltip 运行时行从未显示过）。</li>
 * </ol>
 */
public final class LivingItemRuntimeSync {

    private LivingItemRuntimeSync() {}

    /**
     * 取出本 tick 攒下的脏容器快照并派发（由 L4 的 tick 调用）。
     *
     * <p>⚠️ <b>键形</b>（{@code ContainerIdentity.getCacheKey()}）：玩家背包 / 末影箱 /
     * 掉落物 = 裸键；世界容器 = {@code <维度>|chest_…}。<b>两类都不会互相误判</b>
     * （世界键以维度 id 开头）—— 且现在判定统一走 {@link ContainerKeys#kindOf}（剥前缀后判种类），
     * 不再由本类自己拼前缀规则。</p>
     *
     * @param players            在线玩家
     * @param containerLookup    容器键 → 该容器关联的 {@link Container} 实例集合
     *                           （用于 BE 容器的 viewer 匹配；玩家容器不需要）
     */
    public static void flush(Collection<ServerPlayer> players,
                             java.util.function.Function<String, Collection<Container>> containerLookup) {
        Map<String, Map<Integer, RuntimeSegments>> dirty = ContainerRuntimeCache.drainDirty();
        if (dirty.isEmpty()) return;

        for (var entry : dirty.entrySet()) {
            String containerKey = entry.getKey();
            ContainerKind kind = ContainerKeys.kindOf(containerKey);
            var packet = LivingItemSyncPacket.of(containerKey, entry.getValue());

            if (kind != null && kind.isOwnedByPlayer()) {
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
     * 玩家容器（背包 / 末影箱）：按 UUID 直发<b>容器主人本人</b>。
     *
     * <p>这两类无 BE 实例可匹配（旧逻辑 containerInstances 为空 → 永远不发 →
     * tooltip 全 0），且背包菜单就是 inventoryMenu 本身，无法走菜单匹配。
     * 直发本人也避免与 BE 容器缓存在客户端互相覆盖。</p>
     *
     * <p>🔴 <b>只发本人</b>是隐私红线：背包内容属于玩家私密信息，改动时必须保持按 UUID 精确匹配。</p>
     */
    private static void sendToOwner(Collection<ServerPlayer> players, String containerKey,
                                    LivingItemSyncPacket packet) {
        UUID uuid = ContainerKeys.ownerOf(containerKey);   // 已覆盖 _ender_chest 后缀
        if (uuid == null) return;   // 键形态异常（防御）⇒ 宁可不发
        for (ServerPlayer player : players) {
            if (!player.getUUID().equals(uuid)) continue;
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
        player.connection.send(LivingItemSyncPacket.of(containerKey, snapshot));
    }
}
