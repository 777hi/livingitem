package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家宿主的联机广播（联机可见 · 设计稿规则 6）—— 照 {@code LivingToolPlayerSync} 模式。
 *
 * <ul>
 *   <li><b>按距离定向</b>：R = 32（与 {@code BeaconChestHost} / K2 同口径 ——
 *       两处半径必须一致，否则会出现"看得到箱子光束、看不到玩家光束"）；</li>
 *   <li><b>内容去重</b>：等级 / 增删变化才发（稳态零发包）；</li>
 *   <li><b>整体替换 + 快照</b>：离线 / 换维度的记录自然消失，无需过期清理；</li>
 *   <li><b>自己那一份不发</b>：本机渲染读自己的背包，更实时。</li>
 * </ul>
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

    /** 本 tick 登记的玩家宿主（UUID → 最高激活等级；由 {@code BeaconFunction#tick} 填入）。 */
    private static final Map<UUID, Integer> current = new LinkedHashMap<>();

    /** 每个观察者上次收到的条目（内容去重）。 */
    private static final Map<UUID, List<BeaconPlayerHostPacket.Entry>> lastSent = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(s -> BeaconPlayerHostSync.clear());
    }

    private BeaconPlayerHostSync() {
    }

    /**
     * 登记一个玩家宿主（同一玩家多枚信标取<b>最高等级</b> —— 客户端一人只画一条光束）。
     *
     * <p>只发等级不发颜色：U3 方案 A 下颜色由客户端读世界方块现算（含别人的光束）。</p>
     */
    public static void report(UUID playerId, int level) {
        if (playerId == null || level < 1) {
            return;
        }
        current.merge(playerId, level, Math::max);
    }

    /** 每 tick 末尾调用（容器处理之后）：按距离定向 + 内容去重后发送。 */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        var playerList = level.getServer().getPlayerList();
        List<ServerPlayer> players = playerList.getPlayers();

        // ① 本维度玩家宿主清单（快照后清空登记表）
        List<BeaconPlayerHostPacket.Entry> all = new ArrayList<>(current.size());
        for (Map.Entry<UUID, Integer> e : current.entrySet()) {
            all.add(new BeaconPlayerHostPacket.Entry(e.getKey(), e.getValue()));
        }
        current.clear();

        // ② 逐个观察者：滤出"别人的、且在半径内的"，内容变了才发
        ResourceLocation dimension = level.dimension().location();
        Map<UUID, List<BeaconPlayerHostPacket.Entry>> next = new HashMap<>();
        for (ServerPlayer viewer : players) {
            if (viewer.level() != level) {
                continue;
            }
            List<BeaconPlayerHostPacket.Entry> mine = new ArrayList<>();
            for (BeaconPlayerHostPacket.Entry entry : all) {
                if (entry.playerId().equals(viewer.getUUID())) {
                    continue;   // 自己那份走本机渲染
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

        lastSent.clear();
        lastSent.putAll(next);
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
