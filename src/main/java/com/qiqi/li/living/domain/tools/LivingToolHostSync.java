package com.qiqi.li.living.domain.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.network.LivingToolHostPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 服务端「哪些容器里有活工具」收集 + 广播（{@code K2}）。
 *
 * <p>不开 GUI 时客户端拿不到箱子内容，只能由服务端把"位置 + 活工具 ItemStack"
 * 同步过去，才能渲染记忆射线（以及将来的悬浮模型）。</p>
 *
 * <h3>发送策略</h3>
 * <ul>
 *   <li><b>按距离定向</b>（R = {@value #RADIUS}）—— 全服广播是明确禁止的
 *       （项目已有教训：{@code EnderChannelSyncPacket}）。</li>
 *   <li><b>内容去重</b> —— 与"上次发给该玩家的内容"逐项比较，无变化就不发。
 *       <b>没有节流</b>：内容（位置集合 / ItemStack）变化频率本来就极低
 *       （放置 / 取走 / 耐久），去重之后天然就是按需发送。</li>
 * </ul>
 */
public final class LivingToolHostSync {

    /**
     * 同步半径（格）。
     *
     * <p>⚠️ <b>必须与原版 {@code ServerLevel#destroyBlockProgress} 的广播半径一致</b>
     * （原版源码 {@code if (d0*d0 + d1*d1 + d2*d2 < 1024.0)}，1024 = 32²）。
     * 不一致会造成错位：</p>
     * <ul>
     *   <li><b>比原版大</b> → 玩家看得到活工具，却看不到它的破坏裂纹
     *       ⇒ 明明在挖，却被推断成"闲"（{@code K31} 靠裂纹推断忙碌）。</li>
     *   <li><b>比原版小</b> → 白白浪费可视范围。</li>
     * </ul>
     * <p>顺带与 {@code LivingToolRayRenderer.MAX_DISTANCE}（32）天然一致 ——
     * "要挖哪"（射线）与"正在挖哪"（裂纹）本就该是同一个视野范围。</p>
     */
    public static final double RADIUS = 32.0;

    /** 本 tick 收集到的「有活工具的容器」；由 {@link #flush} 取走并清空。 */
    private static final Map<BlockPos, List<ItemStack>> current = new LinkedHashMap<>();

    /** 每个玩家上次发出的条目（用于内容去重）。 */
    private static final Map<UUID, List<LivingToolHostPacket.Entry>> lastSent = new HashMap<>();

    private LivingToolHostSync() {
    }

    /**
     * 登记一个宿主容器（由 {@link LivingToolFunction#tick} 每 tick 调用）。
     *
     * <p>⚠️ 只在<b>方块容器</b>形态上报 —— 玩家背包与掉落物客户端本来就知道，
     * 不需要同步。</p>
     *
     * <p>⚠️ 这里<b>必须 copy</b>：直接存引用会导致服务端后续修改它（如扣耐久）
     * 时，{@link #lastSent} 里那份也跟着变 ⇒ 去重比较失效、永远检测不到变化。</p>
     */
    public static void report(BlockPos pos, List<ItemStack> tools) {
        if (pos == null || tools == null || tools.isEmpty()) {
            return;
        }
        List<ItemStack> copy = new ArrayList<>(tools.size());
        for (ItemStack tool : tools) {
            copy.add(tool.copy());
        }
        current.put(pos.immutable(), copy);
    }

    /**
     * 每 tick 末尾调用（在所有容器处理完之后）：按距离定向 + 内容去重后发送。
     *
     * <p>⚠️ 调用时机很重要 —— 必须在 {@code processLevelContainers} <b>之后</b>，
     * 这样本 tick 的登记才都已收齐。</p>
     */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }

        List<LivingToolHostPacket.Entry> all = takeSnapshot();
        ResourceLocation dimension = level.dimension().location();

        Map<UUID, List<LivingToolHostPacket.Entry>> next = new HashMap<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;   // 只处理本维度的玩家
            }
            List<LivingToolHostPacket.Entry> mine = filterInRange(all, player);
            if (!same(mine, lastSent.get(player.getUUID()))) {
                player.connection.send(new LivingToolHostPacket(dimension, mine));
            }
            next.put(player.getUUID(), mine);
        }

        // 整体替换 ⇒ 离线 / 换维度的玩家记录自动消失，无需额外的过期清理
        lastSent.clear();
        lastSent.putAll(next);
    }

    /** 清空全部状态（服务端关闭时调用，避免跨存档残留）。 */
    public static void clear() {
        current.clear();
        lastSent.clear();
    }

    private static List<LivingToolHostPacket.Entry> takeSnapshot() {
        if (current.isEmpty()) {
            return List.of();
        }
        List<LivingToolHostPacket.Entry> snapshot = new ArrayList<>(current.size());
        for (Map.Entry<BlockPos, List<ItemStack>> e : current.entrySet()) {
            snapshot.add(new LivingToolHostPacket.Entry(e.getKey(), e.getValue()));
        }
        current.clear();
        return snapshot;
    }

    private static List<LivingToolHostPacket.Entry> filterInRange(
            List<LivingToolHostPacket.Entry> all, ServerPlayer player) {
        Vec3 at = player.position();
        double r2 = RADIUS * RADIUS;
        List<LivingToolHostPacket.Entry> out = new ArrayList<>();
        for (LivingToolHostPacket.Entry entry : all) {
            if (Vec3.atCenterOf(entry.pos()).distanceToSqr(at) < r2) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 逐项比较（位置 + {@code ItemStack.matches}）。条目是个位数，开销可忽略。 */
    private static boolean same(List<LivingToolHostPacket.Entry> a,
                                List<LivingToolHostPacket.Entry> b) {
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            LivingToolHostPacket.Entry x = a.get(i);
            LivingToolHostPacket.Entry y = b.get(i);
            if (!x.pos().equals(y.pos())) {
                return false;
            }
            List<ItemStack> xt = x.tools();
            List<ItemStack> yt = y.tools();
            if (xt.size() != yt.size()) {
                return false;
            }
            for (int j = 0; j < xt.size(); j++) {
                if (!ItemStack.matches(xt.get(j), yt.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }
}
