package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.util.StaticCacheRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 箱子宿主的开盖 + 近处同步（照 {@code LivingToolHostSync} 的 K2 模式）。
 *
 * <h3>两件事</h3>
 * <ol>
 *   <li><b>开盖保持</b>（设计稿 §2.3）：有活信标的箱子 ⇒ 周期性
 *       {@code level.blockEvent(pos, block, 1, 1)} ⇒ 客户端 {@code triggerEvent}
 *       → {@code shouldBeOpen(true)} → 盖平滑打开并保持；信标取走 ⇒
 *       {@code blockEvent(1, 0)} 恢复。每 20 tick 重发 ⇒ 新进入玩家 / 区块重载
 *       也能看到（blockEvent 只发给当时跟踪该区块的玩家，§2.3 坑 3）。
 *       <b>大箱双半箱都发</b>（{@code getAssociatedBlockPositions()}），避免半开半关。
 *       ⚠️ 不动 {@code openersCounter} ⇒ 不误触发陷阱箱红石（§2.3 坑 1）。</li>
 *   <li><b>S2C 同步</b>：宿主半箱位置 + 激活等级，定向 32 格 + 内容去重，
 *       供客户端画垂直光束（{@code BeaconHostClientCache}）。</li>
 * </ol>
 */
public final class BeaconChestHost {

    /** 同步半径（格）—— 与原版破坏裂纹广播半径一致（K2 同款）。 */
    private static final double RADIUS = 32.0;

    /** 开盖重发周期（tick）—— 1 秒（U11 定值）。 */
    private static final long LID_RESEND_TICKS = 20;

    private record Host(BlockPos hostPos, List<BlockPos> lidPositions, int level, long lastLidTick) {
    }

    /** 本 tick 登记的宿主（由 {@code BeaconFunction#tick} 每 tick 填入，flush 取走清空）。 */
    private static final Map<String, Host> current = new LinkedHashMap<>();

    /** 上次 flush 时的宿主（开盖状态 + 上次开盖时刻，用于重发节流与关盖检测）。 */
    private static final Map<String, Host> active = new LinkedHashMap<>();

    /** 每个玩家上次发出的条目（内容去重）。 */
    private static final Map<UUID, List<BeaconHostPacket.Entry>> lastSent = new HashMap<>();

    static {
        // static 缓存清理：登记与 clear() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        StaticCacheRegistry.onServerStop(s -> BeaconChestHost.clear());
    }

    private BeaconChestHost() {
    }

    /**
     * 登记一个箱子宿主（由 {@link BeaconFunction#tick} 每 tick 调用）。
     *
     * @param cacheKey     容器缓存键（带维度前缀，见 {@code ContainerIdentity.getCacheKey}）
     * @param hostPos      活信标所在半箱位置（光束起点；大箱取含信标的那半）
     * @param lidPositions 需要开盖的全部位置（大箱 = 双半箱）
     * @param level        激活等级（tick 已保证 ≥ 1 才登记）
     */
    public static void report(String cacheKey, BlockPos hostPos, List<BlockPos> lidPositions, int level) {
        if (cacheKey == null || hostPos == null || lidPositions == null || lidPositions.isEmpty()) {
            return;
        }
        current.put(cacheKey, new Host(hostPos.immutable(), List.copyOf(lidPositions), level, Long.MIN_VALUE));
    }

    /**
     * 每 tick 末尾调用（在所有容器处理完之后）：关盖（消失的）/ 开盖重发（20 tick）
     * + S2C 定向同步。
     */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        long gameTime = level.getGameTime();

        // ① 关盖：上次开盖、本 tick 不再登记的容器（信标取走 / 箱子卸载）
        for (Map.Entry<String, Host> e : active.entrySet()) {
            if (!current.containsKey(e.getKey())) {
                for (BlockPos pos : e.getValue().lidPositions()) {
                    sendLid(level, pos, false);
                }
            }
        }

        // ② 开盖：本 tick 登记的容器（新容器立即开；已在开的每 20 tick 重发）
        Map<String, Host> nextActive = new LinkedHashMap<>(current.size());
        for (Map.Entry<String, Host> e : current.entrySet()) {
            Host h = e.getValue();
            Host prev = active.get(e.getKey());
            long last = prev != null ? prev.lastLidTick() : Long.MIN_VALUE;
            if (gameTime - last >= LID_RESEND_TICKS) {
                for (BlockPos pos : h.lidPositions()) {
                    sendLid(level, pos, true);
                }
                last = gameTime;
            }
            nextActive.put(e.getKey(), new Host(h.hostPos(), h.lidPositions(), h.level(), last));
        }
        active.clear();
        active.putAll(nextActive);
        current.clear();

        // ③ S2C：位置 + 等级，定向 32 格，内容去重
        List<BeaconHostPacket.Entry> all = new ArrayList<>(active.size());
        for (Host h : active.values()) {
            all.add(new BeaconHostPacket.Entry(h.hostPos(), h.level()));
        }
        ResourceLocation dimension = level.dimension().location();
        Map<UUID, List<BeaconHostPacket.Entry>> next = new HashMap<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;   // 只处理本维度的玩家
            }
            List<BeaconHostPacket.Entry> mine = new ArrayList<>();
            for (BeaconHostPacket.Entry entry : all) {
                if (inRange(player, entry.pos())) {
                    mine.add(entry);
                }
            }
            if (!mine.equals(lastSent.get(player.getUUID()))) {
                player.connection.send(new BeaconHostPacket(dimension, mine));
            }
            next.put(player.getUUID(), mine);
        }
        lastSent.clear();
        lastSent.putAll(next);
    }

    /** 清空全部状态（服务端关闭时调用，避免跨存档残留）。 */
    public static void clear() {
        current.clear();
        active.clear();
        lastSent.clear();
    }

    /** 开/关盖 —— block 在发送时现取（箱子可能已被破坏，取到空气则事件自然落空）。 */
    private static void sendLid(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
    }

    /** 距离判定：玩家到方块中心的球面距离（R² = 32²，与原版破坏裂纹广播一致）。 */
    private static boolean inRange(ServerPlayer player, BlockPos pos) {
        double d0 = player.getX() - (pos.getX() + 0.5);
        double d1 = player.getY() - (pos.getY() + 0.5);
        double d2 = player.getZ() - (pos.getZ() + 0.5);
        return d0 * d0 + d1 * d1 + d2 * d2 < RADIUS * RADIUS;
    }
}
