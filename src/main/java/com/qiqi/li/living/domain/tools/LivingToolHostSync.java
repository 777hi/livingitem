package com.qiqi.li.living.domain.tools;
import com.qiqi.li.living.domain.tools.LivingToolMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.qiqi.li.living.util.BroadcastDedup;
import com.qiqi.li.living.util.StaticCacheRegistry;

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
    private static final Map<BlockPos, List<LivingToolHostPacket.ToolRay>> current = new LinkedHashMap<>();

    /** 每个玩家上次发出的条目（用于内容去重）。 */
    private static Map<UUID, List<LivingToolHostPacket.Entry>> lastSent = new HashMap<>();

    static {
        // static 缓存清理：登记与 clear() 同文件（见 StaticCacheRegistry 类注释「登记位置」）
        StaticCacheRegistry.onServerStop(s -> LivingToolHostSync.clear());
    }

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
    public static void report(BlockPos pos, List<LivingToolHostPacket.ToolRay> tools) {
        if (pos == null || tools == null || tools.isEmpty()) {
            return;
        }
        List<LivingToolHostPacket.ToolRay> copy = new ArrayList<>(tools.size());
        for (LivingToolHostPacket.ToolRay tool : tools) {
            copy.add(new LivingToolHostPacket.ToolRay(
                tool.stack().copy(), tool.digLanded(), tool.useLanded()));
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
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (player.level() != level) {
                continue;   // 只处理本维度的玩家
            }
            List<LivingToolHostPacket.Entry> mine = filterInRange(all, player);
            if (!same(mine, lastSent.get(player.getUUID()))) {
                player.connection.send(new LivingToolHostPacket(dimension, mine));
            }
            next.put(player.getUUID(), mine);
        }

        // ⚠️ 不能整体 clear：本方法【逐维度】调用（server.getAllLevels() 恒有主世界/地狱/末地
        // 三个），整体清会把**其它维度玩家**的记录也抹掉 ⇒ 他们下一 tick 的
        // `lastSent.get(uuid)` 变 null ⇒ `same` 判「有变化」⇒ 每 tick 重发一个空包。
        // 2026-10-23 日志实录：单条 INFO 刷了 3803 次（玩家只有一个，包却每 tick 都在发）。
        // 合并口径抽在 BroadcastDedup（四个同步器共用一份 —— 抄四份就会只修一份）。
        lastSent = BroadcastDedup.merge(lastSent, next, online);
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
        for (Map.Entry<BlockPos, List<LivingToolHostPacket.ToolRay>> e : current.entrySet()) {
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

    /**
     * 逐项比较（位置 + 工具的<b>渲染等价性</b>）。条目是个位数，开销可忽略。
     *
     * <p>⚠️ <b>为什么不用 {@code ItemStack.matches}</b>：本包只为渲染服务，而
     * {@code ItemStack.matches} 比<b>全部组件</b> —— 其中耐久随每次破坏变化，比它只会
     * 带来无意义的重发（2026-09-19 实测：退化为每 tick 一个包）。</p>
     *
     * <p>⚠️ 但<b>不能因此把动画状态一起排除</b>：客户端渲染用的是<b>本包带来的</b> ItemStack
     * 副本（{@code LivingToolHostClientCache}），不是容器槽位的实时内容 ⇒ 漏比一个字段，
     * 客户端就永远收不到那个字段的变化。哪些字段算"渲染依赖"见 {@link #sameTool}。</p>
     *
     * <p>包内可见：{@code LivingToolHostSyncDedupTest} 直接钉这条口径。</p>
     */
    static boolean same(List<LivingToolHostPacket.Entry> a,
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
            List<LivingToolHostPacket.ToolRay> xt = x.tools();
            List<LivingToolHostPacket.ToolRay> yt = y.tools();
            if (xt.size() != yt.size()) {
                return false;
            }
            for (int j = 0; j < xt.size(); j++) {
                if (!sameRay(xt.get(j), yt.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 一把工具的同步条目是否等价 —— 命中布尔 + 渲染关心的物品字段。 */
    private static boolean sameRay(LivingToolHostPacket.ToolRay a, LivingToolHostPacket.ToolRay b) {
        return a.digLanded() == b.digLanded()
            && a.useLanded() == b.useLanded()
            && sameTool(a.stack(), b.stack());
    }

    /**
     * 两把工具是否「渲染等价」—— 物品种类 + {@link ToolRenderState}（记忆 + 动画状态）。
     *
     * <p>⚠️ <b>渲染依赖的清单只有一份</b>，住在 {@code ToolRenderState}：客户端渲染从它取，
     * 这里也从它取 ⇒ 加字段只改那一个文件。曾经这里手写一份清单、渲染器手写另一份，
     * 漏改一边就是静默失效（2026-10-26 事故）。</p>
     *
     * <p>耐久等与渲染无关的字段<b>不进</b> {@code ToolRenderState} —— 它们随每次破坏变化，
     * 比了就是无意义的重发。即判据是「渲染依赖」而非「内容相等」，两边都要守住：
     * 多比一个会刷包，少比一个会没动画。</p>
     */
    private static boolean sameTool(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty();
        }
        if (a.getItem() != b.getItem()) {
            return false;
        }
        return ToolRenderState.of(a).equals(ToolRenderState.of(b));
    }
}
