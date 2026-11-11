package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.util.BroadcastDedup;
import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 实体宿主的联机广播（船 / 矿车 / 生物背包 / 掉落物 · D-container-25）
 * —— 照 {@link BeaconPlayerHostSync} 模式。
 *
 * <ul>
 *   <li><b>按距离定向</b>：R = 256（{@link #RADIUS}，对齐原版
 *       {@code BeaconRenderer.getViewDistance()}，与玩家 / 箱子宿主同口径）；</li>
 *   <li><b>内容去重</b>：等级 / 增删变化才发（稳态零发包）；</li>
 *   <li><b>整体替换 + 快照</b>：实体卸载 / 换维度的记录自然消失，无需过期清理；</li>
 *   <li><b>没有「自己那份不发」</b>：信标在宿主实体身上，主人物品栏里没有它
 *       ⇒ 所有跟踪该实体的观察者（含主人）都靠本包。</li>
 * </ul>
 */
public final class BeaconEntityHostSync {

    /** 广播半径（格）—— 对齐原版，与玩家 / 箱子宿主同口径。 */
    public static final double RADIUS = 256.0;

    /** 本 tick 登记的实体宿主（一实体多枚信标取最高等级 ⇒ 一实体一条光束，与玩家口径一致）。 */
    private record Host(int level, List<Integer> glassColors) {
    }

    /** 本 tick 登记的实体宿主（由 {@code BeaconFunction#tick} 填入）。 */
    private static final Map<UUID, Host> current = new LinkedHashMap<>();

    /** 每个观察者上次收到的条目（内容去重）。 */
    private static Map<UUID, List<BeaconEntityHostPacket.Entry>> lastSent = new HashMap<>();

    static {
        StaticCacheRegistry.onServerStop(s -> BeaconEntityHostSync.clear());
    }

    private BeaconEntityHostSync() {
    }

    /**
     * 登记一个实体宿主（同一实体多枚信标取<b>最高等级</b>——等级、玻璃色跟最高那枚走）。
     */
    public static void report(UUID entityId, int level, List<Integer> glassColors) {
        if (entityId == null || level < 1) {
            return;
        }
        Host prev = current.get(entityId);
        if (prev == null || level >= prev.level()) {
            current.put(entityId, new Host(level, List.copyOf(glassColors)));
        }
    }

    /**
     * <b>每 tick 开头清空登记表</b>（由 {@code LivingItem#onServerTick} 调用）。
     *
     * <p>清表不能放在 {@link #flush} 里：flush 是<b>逐维度</b>调的，而登记表是全服一张
     * （跨维度铁律，见 {@code BeaconPlayerHostSync} 类注释同款）。</p>
     */
    public static void beginTick() {
        current.clear();
    }

    /** 每 tick 末尾调用（容器处理之后）：按距离定向 + 内容去重后发送。 */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();

        // ① 全服登记（⚠️ 不在此清空——跨维度铁律，清理由 beginTick() 负责）
        List<BeaconEntityHostPacket.Entry> all = new ArrayList<>(current.size());
        for (Map.Entry<UUID, Host> e : current.entrySet()) {
            all.add(new BeaconEntityHostPacket.Entry(e.getKey(), e.getValue().level(),
                e.getValue().glassColors()));
        }

        // ② 逐个观察者：滤出"实体在本维度且在半径内的"，内容变了才发
        ResourceLocation dimension = level.dimension().location();
        Map<UUID, List<BeaconEntityHostPacket.Entry>> next = new HashMap<>();
        for (ServerPlayer viewer : players) {
            if (viewer.level() != level) {
                continue;
            }
            List<BeaconEntityHostPacket.Entry> mine = new ArrayList<>();
            for (BeaconEntityHostPacket.Entry entry : all) {
                Entity carrier = level.getEntity(entry.entityId());
                if (carrier == null || carrier.isRemoved()) {
                    continue;   // 实体在其它维度 / 已卸载 ⇒ 本维度不发
                }
                if (withinHorizontal(carrier, viewer)) {
                    mine.add(entry);
                }
            }
            if (!mine.equals(lastSent.get(viewer.getUUID()))) {
                viewer.connection.send(new BeaconEntityHostPacket(dimension, mine));
            }
            next.put(viewer.getUUID(), mine);
        }

        // ⚠️ 逐维度调用 + 一张全服表 ⇒ 不能整体 clear（见 BroadcastDedup）
        Set<UUID> online = new HashSet<>(players.size());
        for (ServerPlayer p : players) {
            online.add(p.getUUID());
        }
        lastSent = BroadcastDedup.merge(lastSent, next, online);
    }

    /** 清空全部状态（服务端关闭时调用，避免跨存档残留）。 */
    public static void clear() {
        current.clear();
        lastSent.clear();
    }

    /** 距离判定：<b>只算水平距离</b>（Y 清零）—— 与玩家 / 箱子宿主同口径。 */
    private static boolean withinHorizontal(Entity carrier, ServerPlayer viewer) {
        double dx = carrier.getX() - viewer.getX();
        double dz = carrier.getZ() - viewer.getZ();
        return dx * dx + dz * dz < RADIUS * RADIUS;
    }
}
