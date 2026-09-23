package com.qiqi.li.living.domain.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.network.LivingToolPlayerPacket;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 服务端：把每个玩家背包里的「无记忆活工具」按距离广播给附近客户端
 * （联机可见性 · <b>最小版</b>：只让待机环可见）。
 *
 * <p>设计完全对齐 {@link LivingToolHostSync}（方块容器那套）：</p>
 * <ul>
 *   <li><b>按距离定向</b>：{@link #RADIUS} 必须与 {@code LivingToolHostSync.RADIUS}
 *       （32）一致 —— 两者都是"活工具的可视范围"，不一致会出现"看得到环、看不到射线"。</li>
 *   <li><b>只在内容变化时才发</b>（逐项比较渲染等价性）—— 内容变化频率本就极低。</li>
 *   <li><b>整体替换 + 快照</b>：每 tick 重建 {@link #lastSent}，离线 / 换维度的记录自然消失，
 *       不需要额外的过期清理。</li>
 * </ul>
 *
 * <p>⚠️ <b>自己那一份不发</b>：自己的工具由本机渲染（读自己的背包，更实时、且能走挖掘环）。
 * 包里只放"别人需要知道的东西"。</p>
 *
 * <p>⚠️ <b>最小版不含挖掘环</b>：{@code LivingToolAssistState} 是各机器本地记录的，
 * 要同步"他正在挖哪一格"得额外广播。</p>
 */
public final class LivingToolPlayerSync {

    /** 广播半径（格）。⚠️ 必须与 {@code LivingToolHostSync.RADIUS} 一致（见类注释）。 */
    public static final double RADIUS = 32.0;

    /** 每个观察者上次收到的条目（用于内容去重）。 */
    private static final Map<UUID, List<LivingToolPlayerPacket.Entry>> lastSent = new HashMap<>();

    private LivingToolPlayerSync() {
    }

    /**
     * 每 tick 末尾调用：按距离定向 + 内容去重后发送。
     *
     * <p>⚠️ 必须收齐本 tick 的背包状态后再调用（放在 {@code onServerTick} 的末尾）。</p>
     */
    public static void flush(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        var playerList = level.getServer().getPlayerList();
        List<ServerPlayer> players = playerList.getPlayers();

        // ① 本维度所有玩家的"工具清单"（内容为空的不进包）
        List<LivingToolPlayerPacket.Entry> all = new ArrayList<>();
        for (ServerPlayer owner : players) {
            if (owner.level() != level) {
                continue;
            }
            List<ItemStack> tools = collectAssistTools(owner);
            if (!tools.isEmpty()) {
                all.add(new LivingToolPlayerPacket.Entry(owner.getUUID(), tools));
            }
        }

        // ② 逐个观察者：滤出"别人的、且在半径内的"，内容变了才发
        ResourceLocation dimension = level.dimension().location();
        double r2 = RADIUS * RADIUS;
        Map<UUID, List<LivingToolPlayerPacket.Entry>> next = new HashMap<>();
        for (ServerPlayer viewer : players) {
            if (viewer.level() != level) {
                continue;
            }
            List<LivingToolPlayerPacket.Entry> mine = new ArrayList<>();
            for (LivingToolPlayerPacket.Entry entry : all) {
                if (entry.playerId().equals(viewer.getUUID())) {
                    continue;   // 自己那份走本机渲染
                }
                ServerPlayer owner = playerList.getPlayer(entry.playerId());
                if (owner != null && owner.position().distanceToSqr(viewer.position()) < r2) {
                    mine.add(entry);
                }
            }
            if (!same(mine, lastSent.get(viewer.getUUID()))) {
                viewer.connection.send(new LivingToolPlayerPacket(dimension, mine));
            }
            next.put(viewer.getUUID(), mine);
        }

        lastSent.clear();
        lastSent.putAll(next);
    }

    /** 清空全部状态（服务端关闭时调用，避免跨存档残留）。 */
    public static void clear() {
        lastSent.clear();
    }

    /**
     * 玩家背包里的「无记忆活工具」（排除手持 —— 手持的由物品自身渲染）。
     *
     * <p>⚠️ 必须 {@code copy()}：直接存引用会让服务端后续修改（扣耐久等）把
     * {@link #lastSent} 里那份也改掉 ⇒ 去重比较失效、永远检测不到变化
     * （{@link LivingToolHostSync#report} 踩过同一个坑）。</p>
     */
    private static List<ItemStack> collectAssistTools(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        Inventory inventory = player.getInventory();
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == mainHand || stack == offHand) {
                continue;
            }
            if (LivingToolRecorder.isAssistItem(stack)) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /** 逐项比较（玩家 + 工具的<b>渲染等价性</b>）。条目是个位数，开销可忽略。 */
    private static boolean same(List<LivingToolPlayerPacket.Entry> a,
                                List<LivingToolPlayerPacket.Entry> b) {
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            LivingToolPlayerPacket.Entry x = a.get(i);
            LivingToolPlayerPacket.Entry y = b.get(i);
            if (!x.playerId().equals(y.playerId()) || x.tools().size() != y.tools().size()) {
                return false;
            }
            for (int j = 0; j < x.tools().size(); j++) {
                if (!ItemStack.isSameItemSameComponents(x.tools().get(j), y.tools().get(j))) {
                    return false;
                }
            }
        }
        return true;
    }
}
