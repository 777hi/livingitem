package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.qiqi.li.living.util.BroadcastDedup;
import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家宿主的联机广播（联机可见 · 设计稿规则 6）—— 照 {@code LivingToolPlayerSync} 模式。
 *
 * <ul>
 *   <li><b>按距离定向</b>：R = 256（{@link #RADIUS}，对齐原版
 *       {@code BeaconRenderer.getViewDistance()}，与 {@code BeaconChestHost.RADIUS} 同口径
 *       —— 两处半径必须一致，否则会出现"看得到箱子光束、看不到玩家光束"）；</li>
 *   <li><b>内容去重</b>：等级 / 增删变化才发（稳态零发包）；</li>
 *   <li><b>整体替换 + 快照</b>：离线 / 换维度的记录自然消失，无需过期清理；</li>
 *   <li><b>自己那一份不发</b>：本机渲染读自己的背包，更实时。</li>
 * </ul>
 *
 * <p>⚠️ <b>跨维度铁律（2026-10-18 审查发现并修）</b>：本类的登记表是<b>全服一张</b>（玩家背包
 * 对所有维度统一处理），而 {@code flush} 是<b>逐维度</b>调用的 ⇒ 登记表<b>绝不能在 flush 里清</b>
 * ——原先在 flush 末尾 {@code clear()}，第一个维度就把全表抽干，后续维度的 flush 恒拿到空表
 * ⇒ <b>多维度服务器上玩家宿主光束只在第一个维度有效</b>。清理由
 * {@link #beginTick()} 在 tick 开头（所有容器处理之前）统一做一次。</p>
 */
public final class BeaconPlayerHostSync {

    /**
     * 广播半径（格）—— <b>256，对齐原版</b>（{@code BeaconRenderer.getViewDistance()}，
     * 与 {@code BeaconChestHost.RADIUS} 保持一致）。
     *
     * <p>玩家宿主的<b>实际</b>上限另有天花板：对方玩家实体本身要同步给你（玩家实体
     * {@code clientTrackingRange = 32} 区块，实际受你的视距限制）⇒ 半径 256 已足够
     * "你看得见他，就看得见他的光束"。</p>
     */
    public static final double RADIUS = 256.0;

    /**
     * 本 tick 登记的玩家宿主 —— 键是<b>宿主玩家</b>（同一玩家多枚信标取最高等级，一人一条光束）。
     *
     * <p>{@code containerUuid} 只记录「最高那枚信标是不是在<b>物品形态容器</b>里」，
     * 用于决定这条要不要<b>也发给他自己</b> —— 物品形态的内容在服务端 SavedData、
     * 客户端读不到（见 {@code BeaconPlayerHostPacket.Entry}）。</p>
     */
    private record Host(int level, List<Integer> glassColors, @Nullable UUID containerUuid) {}

    /** 本 tick 登记的玩家宿主（由 {@code BeaconFunction#tick} 填入）。 */
    private static final Map<UUID, Host> current = new LinkedHashMap<>();

    /** 每个观察者上次收到的条目（内容去重）。 */
    private static Map<UUID, List<BeaconPlayerHostPacket.Entry>> lastSent = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(s -> BeaconPlayerHostSync.clear());
    }

    private BeaconPlayerHostSync() {
    }

    /**
     * 登记一个玩家宿主（同一玩家多枚信标取<b>最高等级</b> —— 客户端一人只画一条光束）。
     *
     * <p>世界玻璃的颜色不发（U3-A：客户端读世界方块现算）；<b>容器玻璃色随条目下发</b>——
     * 客户端看不到别人背包内容（2026-10-16 用户需求「信标上方槽位的玻璃也影响颜色」联机侧）。
     * 多枚信标取最高的那枚的玻璃色（与等级同源，一人一条光束）。</p>
     */
    public static void report(UUID playerId, @Nullable UUID containerUuid, int level,
                              List<Integer> glassColors) {
        if (playerId == null || level < 1) {
            return;
        }
        Host prev = current.get(playerId);
        if (prev == null || level >= prev.level()) {
            // 取最高等级那枚：等级、玻璃色、来源一并跟它走（同等级用后者，与原 merge 语义一致）
            current.put(playerId, new Host(level, List.copyOf(glassColors), containerUuid));
        }
    }

    /**
     * <b>每 tick 开头清空登记表</b>（由 {@code LivingItem#onServerTick} 在玩家循环之前调用）。
     *
     * <p>为什么不能放在 {@link #flush} 里：flush 是<b>逐维度</b>调的，而登记表是全服一张
     * （见类注释「跨维度铁律」）。单测用两个 {@code ServerLevel} 替身驱动 flush，两次都拿到
     * 全量条目、各自按 {@code owner.level()} 过滤 ⇒ 维度隔离成立。</p>
     */
    public static void beginTick() {
        current.clear();
    }

    /** 每 tick 末尾调用（容器处理之后）：按距离定向 + 内容去重后发送。 */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        var playerList = level.getServer().getPlayerList();
        List<ServerPlayer> players = playerList.getPlayers();
        // 全服在线玩家（⚠️ 不是本维度的：merge 用它淘汰离线记录，用本维度会误删其它维度玩家）
        Set<UUID> online = new HashSet<>(players.size());
        for (ServerPlayer p : players) {
            online.add(p.getUUID());
        }

        // ① 全服登记（⚠️ 不在此清空——跨维度铁律见类注释，清理由 beginTick() 负责）
        List<BeaconPlayerHostPacket.Entry> all = new ArrayList<>(current.size());
        for (Map.Entry<UUID, Host> e : current.entrySet()) {
            Host h = e.getValue();
            all.add(new BeaconPlayerHostPacket.Entry(e.getKey(), h.level(), h.glassColors(),
                h.containerUuid()));
        }

        // ② 逐个观察者：滤出"别人的、且在半径内的"，内容变了才发
        ResourceLocation dimension = level.dimension().location();
        Map<UUID, List<BeaconPlayerHostPacket.Entry>> next = new HashMap<>();
        for (ServerPlayer viewer : players) {
            if (viewer.level() != level) {
                continue;
            }
            List<BeaconPlayerHostPacket.Entry> mine = new ArrayList<>();
            for (BeaconPlayerHostPacket.Entry entry : all) {
                if (entry.playerId().equals(viewer.getUUID()) && !entry.fromItemForm()) {
                    // 自己那份（可见形态）走本机渲染，更实时；
                    // 但**物品形态容器的内容客户端读不到** ⇒ 那条也得发给自己（2026-10-26）
                    continue;
                }
                ServerPlayer owner = playerList.getPlayer(entry.playerId());
                if (owner != null && owner.level() == level && withinHorizontal(owner, viewer)) {
                    mine.add(entry);
                }
            }
            if (!mine.equals(lastSent.get(viewer.getUUID()))) {
                viewer.connection.send(new BeaconPlayerHostPacket(dimension, mine));
            }
            next.put(viewer.getUUID(), mine);
        }

        // ⚠️ 逐维度调用 + 一张全服表 ⇒ 不能整体 clear（见 BroadcastDedup）
        lastSent = BroadcastDedup.merge(lastSent, next, online);
    }

    /** 清空全部状态（服务端关闭时调用，避免跨存档残留）。 */
    public static void clear() {
        current.clear();
        lastSent.clear();
    }

    /**
     * 距离判定：<b>只算水平距离</b>（Y 清零）—— 对齐原版 {@code BeaconRenderer.shouldRender}
     * 的判据，与 {@code BeaconChestHost} 同口径。
     */
    private static boolean withinHorizontal(ServerPlayer owner, ServerPlayer viewer) {
        double dx = owner.getX() - viewer.getX();
        double dz = owner.getZ() - viewer.getZ();
        return dx * dx + dz * dz < RADIUS * RADIUS;
    }
}
