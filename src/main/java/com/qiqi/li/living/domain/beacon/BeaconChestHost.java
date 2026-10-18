package com.qiqi.li.living.domain.beacon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *       ⚠️ 不动 {@code openersCounter} ⇒ 不误触发陷阱箱红石（§2.3 坑 1）。
 *       ⚠️ 开盖<b>按容器</b>而非按信标：一箱两枚信标取走一枚，盖还得开着。</li>
 *   <li><b>S2C 同步</b>：<b>每枚信标一条</b>宿主条目（位置 = 信标所在半箱 + 激活等级 + 该枚
 *       信标同列向上的容器玻璃色），定向 256 格 + 内容去重，供客户端画垂直光束
 *       （{@code BeaconHostClientCache}）。2026-10-18 用户定「同容器多枚信标每枚一条」，
 *       取代原「按容器 merge（等级取 max、颜色取胜出者）」——现在每枚用自己的等级与颜色。</li>
 * </ol>
 */
public final class BeaconChestHost {

    /**
     * 同步半径（格）—— <b>256，对齐原版</b>（{@code BeaconRenderer.getViewDistance()}，
     * 2026-10-16 用户定「和原版差不多」，取代 K2 的 32）。
     *
     * <p>代价可忽略：条目只有位置 + 等级 + 玻璃色（几十字节），且<b>只在内容变化时才发</b>
     * （不是每 tick）⇒ 稳态零流量。</p>
     */
    private static final double RADIUS = 256.0;

    /** 开盖重发周期（tick）—— 1 秒（U11 定值）。 */
    private static final long LID_RESEND_TICKS = 20;

    /**
     * 「从未开盖」哨兵 —— ⚠️ 不能用 {@code Long.MIN_VALUE}：{@code gameTime - Long.MIN_VALUE}
     * 会 **long 溢出成负数** ⇒ 重发条件永假 ⇒ 一次都没发过 blockEvent（2026-10-16 用户实测
     * 「箱子不会保持盖子开启」的根因）。gameTime 非负 ⇒ {@code -1} 安全无溢出。
     */
    private static final long NEVER = -1L;

    /**
     * 一个宿主条目 = <b>一枚信标</b>（不是「一个容器」——2026-10-18 每枚一条）。
     *
     * @param containerKey 所属容器缓存键（开盖 / 关盖按它归组：同箱多枚共享一份盖状态）
     * @param hostPos      活信标所在半箱位置（光束起点；大箱取含信标的那半）
     * @param lidPositions 需要开盖的全部位置（大箱 = 双半箱；同箱多枚信标的值相同）
     * @param level        该枚信标的激活等级（tick 已保证 ≥ 1 才登记）
     * @param glassColors  <b>该枚</b>信标同列向上的容器玻璃色序列（自下而上；空 = 无）
     */
    record Host(String containerKey, BlockPos hostPos, List<BlockPos> lidPositions, int level,
                List<Integer> glassColors) {
    }

    /** 本 tick 登记的宿主（每枚信标一条，由 {@code BeaconFunction#tick} 填入，flush 取走清空）。 */
    private static final Map<String, Host> current = new LinkedHashMap<>();

    /** 上次 flush 时的宿主（S2C 内容去重的基线）。 */
    private static final Map<String, Host> active = new LinkedHashMap<>();

    /** 每个容器上次开盖时刻（按容器节流——一箱多枚信标共享一份开盖状态）。 */
    private static final Map<String, Long> lidLastTick = new HashMap<>();

    /** 上次 flush 时开着盖的容器 → 盖位置（关盖检测：整箱再无信标才关）。 */
    private static final Map<String, List<BlockPos>> activeLids = new LinkedHashMap<>();

    /** 每个玩家上次发出的条目（内容去重）。 */
    private static final Map<UUID, List<BeaconHostPacket.Entry>> lastSent = new HashMap<>();

    static {
        // static 缓存清理：登记与 clear() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        StaticCacheRegistry.onServerStop(s -> BeaconChestHost.clear());
    }

    private BeaconChestHost() {
    }

    /**
     * 每信标宿主键 = 容器缓存键 + {@code "#"} + 槽位（2026-10-18 每枚一条的键控基础）。
     *
     * <p>格式必须稳定：它只在本类内部当日志/键用，不跨会话持久化 ⇒ 改格式无兼容负担；
     * 但 <b>槽位区分不能丢</b>——丢了两枚信标会互相覆盖，又回到「按容器 merge」。</p>
     */
    static String hostKey(String cacheKey, int slot) {
        return cacheKey + "#" + slot;
    }

    /**
     * 登记一枚信标宿主（由 {@link BeaconFunction#tick} 每 tick 对每个已激活信标调用）。
     *
     * @param cacheKey     容器缓存键（带维度前缀，见 {@code ContainerIdentity.getCacheKey}）
     * @param slot         信标在容器里的槽位（区分同容器的多枚信标）
     * @param hostPos      活信标所在半箱位置（光束起点；大箱取含信标的那半）
     * @param lidPositions 需要开盖的全部位置（大箱 = 双半箱）
     * @param level        激活等级（tick 已保证 ≥ 1 才登记）
     * @param glassColors  信标同列向上的容器玻璃色序列（自下而上；空 = 无）
     */
    public static void report(String cacheKey, int slot, BlockPos hostPos, List<BlockPos> lidPositions,
                              int level, List<Integer> glassColors) {
        if (cacheKey == null || slot < 0 || hostPos == null || lidPositions == null
                || lidPositions.isEmpty()) {
            return;
        }
        current.put(hostKey(cacheKey, slot), new Host(cacheKey, hostPos.immutable(),
            List.copyOf(lidPositions), level, List.copyOf(glassColors)));
    }

    /**
     * 宿主 → 发包条目；<b>完全相同的条目去重</b>（包级私有供单测）。
     *
     * <p>同箱两枚信标若等级与玻璃色全同（同列、同样配置）⇒ 两条重合光束与一条无异，
     * 画两遍只会双重混合变亮 ⇒ 去重。颜色 / 位置 / 等级任一不同都保留（那是「每枚一条」
     * 要的效果：大箱两半不同位置、同箱不同列不同色）。</p>
     */
    static List<BeaconHostPacket.Entry> assembleEntries(Collection<Host> hosts) {
        LinkedHashSet<BeaconHostPacket.Entry> distinct = new LinkedHashSet<>();
        for (Host h : hosts) {
            distinct.add(new BeaconHostPacket.Entry(h.hostPos(), h.level(), h.glassColors()));
        }
        return List.copyOf(distinct);
    }

    /**
     * 每 tick 末尾调用（在所有容器处理完之后）：关盖（整箱再无信标）/ 开盖重发（20 tick）
     * + S2C 定向同步（每枚信标一条）。
     */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        long gameTime = level.getGameTime();

        // ① 本 tick 有信标的容器 → 盖位置（同箱多枚信标共享一份；值相同，取先登记的即可）
        Map<String, List<BlockPos>> liveLids = new LinkedHashMap<>();
        for (Host h : current.values()) {
            liveLids.putIfAbsent(h.containerKey(), h.lidPositions());
        }

        // ② 关盖：上次开着、本 tick 整箱再无信标（信标取走 / 箱子卸载）
        //    ⚠️ 按容器判：取走一枚 ≠ 关盖，只要还有信标在登记就不关
        for (Map.Entry<String, List<BlockPos>> e : activeLids.entrySet()) {
            if (!liveLids.containsKey(e.getKey())) {
                for (BlockPos pos : e.getValue()) {
                    sendLid(level, pos, false);
                }
                lidLastTick.remove(e.getKey());   // 清节流 ⇒ 下次开盖立即发
            }
        }

        // ③ 开盖：本 tick 有信标的容器（新容器立即开；已在开的每 20 tick 重发，按容器节流）
        for (Map.Entry<String, List<BlockPos>> e : liveLids.entrySet()) {
            long last = lidLastTick.getOrDefault(e.getKey(), NEVER);
            if (last == NEVER || gameTime - last >= LID_RESEND_TICKS) {
                for (BlockPos pos : e.getValue()) {
                    sendLid(level, pos, true);
                }
                lidLastTick.put(e.getKey(), gameTime);
            }
        }
        activeLids.clear();
        activeLids.putAll(liveLids);

        // ④ S2C：每枚信标一条（同箱多枚 = 同位置多条、颜色各自不同；全同的去重）
        active.clear();
        active.putAll(current);
        current.clear();

        List<BeaconHostPacket.Entry> all = assembleEntries(active.values());
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
        lidLastTick.clear();
        activeLids.clear();
        lastSent.clear();
    }

    /** 开/关盖 —— block 在发送时现取（箱子可能已被破坏，取到空气则事件自然落空）。 */
    private static void sendLid(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
    }

    /**
     * 距离判定：<b>只算水平距离</b>（Y 清零）—— 对齐原版 {@code BeaconRenderer.shouldRender}
     * 的判据（{@code Vec3.atCenterOf(pos).multiply(1,0,1).closerThan(camera.multiply(1,0,1), 256)}），
     * 这样"谷底看山顶的箱子光束"也成立。
     */
    private static boolean inRange(ServerPlayer player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz < RADIUS * RADIUS;
    }
}
