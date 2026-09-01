package com.qiqi.li.living.domain.ender;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.components.ItemFilterComponent;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.network.EnderChannelSyncPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * 活末影箱全局路由表 —— 服务端单例，维护频道键→路由条目双端队列的映射。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>不存储物品，只存储路由指针（物品类型 + 维度 + 位置 + 槽位）</li>
 *   <li>物品始终留在源容器中，由活末影箱的漏斗负责传输</li>
 *   <li>轮询公平调度：用 {@link Deque} 的 poll/reoffer 天然实现循环，
 *       取出头部 → 验证 → 有效则提取，源还有物品则放回尾部</li>
 *   <li>频道隔离：频道键 = {@link EnderChannelKey}（归属玩家 + 堆叠数），不同频道互不干扰</li>
 * </ul>
 *
 * <h3>频道键</h3>
 * <p>v12 起频道键由 {@code int} 升级为 {@link EnderChannelKey}：</p>
 * <ul>
 *   <li>{@code (null, N)} —— 公共频道，未绑定玩家的活末影箱</li>
 *   <li>{@code (uuid, N≥2)} —— 玩家专属频道，绑定玩家且堆叠数 ≥ 2</li>
 *   <li>{@code (uuid, 1)} —— 直连模式，<b>不进入本路由表</b></li>
 * </ul>
 * <p>频道键是<b>命名空间</b>而非<b>权限</b>：绑定信息存在物品的 DataComponent 中并跟随物品流转，
 * 谁持有活末影箱谁就能接入其指向的频道。因此同步包依然广播给所有在线玩家，不做定向投递。</p>
 *
 * <h3>反向索引</h3>
 * <ul>
 *   <li>维护 sourcePos → entries 和 containerKey → entries 的反向索引</li>
 *   <li>清理时直接查询相关路由，不需要遍历所有频道</li>
 *   <li>时间复杂度从 O(所有路由) 优化到 O(相关路由)</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 所有操作在服务端 tick 线程中执行，无需额外同步。
 * 客户端通过 {@link EnderChannelSyncPacket} 接收路由快照，
 * 存储在 {@link EnderChannelClientCache} 中供 Tooltip 读取，
 * 不直接访问本注册表。
 */
public final class EnderChannelRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EnderChannelRegistry INSTANCE = new EnderChannelRegistry();

    private MinecraftServer server;

    /**
     * 路由键 —— 用于 O(1) 去重，不存储完整 entry 的所有字段。
     */
    private record RouteKey(BlockPos sourcePos, int sourceSlot, String itemType, String containerKey) {
        static RouteKey of(EnderChannelEntry e) {
            return new RouteKey(e.sourcePos(), e.sourceSlot(), e.itemType(), e.containerKey());
        }
    }

    private static class ChannelData {
        final Deque<EnderChannelEntry> entries = new ArrayDeque<>();
        final Set<RouteKey> routeKeys = new HashSet<>();
    }

    private final Map<EnderChannelKey, ChannelData> channels = new HashMap<>();

    /** 反向索引：方块位置 → 路由条目列表 */
    private final Map<BlockPos, List<EnderChannelEntry>> posIndex = new HashMap<>();

    /** 反向索引：容器 key → 路由条目列表（按 sourcePos/containerKey 索引） */
    private final Map<String, List<EnderChannelEntry>> keyIndex = new HashMap<>();

    /** 反向索引：注册者容器 key → 路由条目列表（按 registrarContainerKey 索引） */
    private final Map<String, List<EnderChannelEntry>> registrarKeyIndex = new HashMap<>();

    /** 条目→频道反向索引：用于 O(1) 查找条目所属频道 */
    private final Map<EnderChannelEntry, EnderChannelKey> entryToChannel = new HashMap<>();

    /** 延迟同步：本 tick 内被修改的频道集合，tick 末尾统一同步 */
    private final Set<EnderChannelKey> dirtyChannels = new HashSet<>();

    private EnderChannelRegistry() {}

    public static EnderChannelRegistry getInstance() {
        return INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    private void syncChannelToAll(EnderChannelKey key) {
        dirtyChannels.add(key);
    }

    /**
     * 延迟同步：将本 tick 内所有被修改的频道统一打包广播。
     *
     * <p>频道键是命名空间而非权限，专属频道路由不视为隐私信息，
     * 因此沿用广播策略（一个包发给所有在线玩家），不存在定向投递与登录补偿的需求。</p>
     */
    public void flushDirtyChannels() {
        if (dirtyChannels.isEmpty()) return;
        for (EnderChannelKey key : dirtyChannels) {
            ChannelData data = channels.get(key);
            int channelSize = data == null ? 0 : data.entries.size();
            List<EnderChannelEntry> entryList = data == null ? List.of() : List.copyOf(data.entries);

            EnderChannelSyncPacket packet = EnderChannelSyncPacket.fromRegistry(key, channelSize, entryList);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
        dirtyChannels.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    // 热路径：单元素操作，O(1)，不触发迭代器
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检查指定频道中是否已存在匹配的路由条目（O(1)，通过 RouteKey 集合）。
     */
    public boolean contains(EnderChannelKey key, EnderChannelEntry entry) {
        ChannelData data = channels.get(key);
        return data != null && data.routeKeys.contains(RouteKey.of(entry));
    }

    /**
     * 向指定频道注册路由条目（去重：O(1) 通过 RouteKey 集合）。
     *
     * @param key   频道键
     * @param entry 路由条目
     * @return true 如果确实新增了条目
     */
    public boolean offer(EnderChannelKey key, EnderChannelEntry entry) {
        ChannelData data = channels.computeIfAbsent(key, k -> new ChannelData());
        RouteKey rk = RouteKey.of(entry);

        if (!data.routeKeys.add(rk)) {
            LOGGER.trace("EnderChannelRegistry: offer duplicate skipped key={}, item={}, pos={}, slot={}",
                key, entry.itemType(), entry.sourcePos(), entry.sourceSlot());
            return false;
        }

        data.entries.offerLast(entry);
        entryToChannel.put(entry, key);
        addToIndex(entry);
        LOGGER.trace("EnderChannelRegistry: offer key={}, item={}, pos={}, slot={}, size={}",
            key, entry.itemType(), entry.sourcePos(), entry.sourceSlot(), data.entries.size());
        syncChannelToAll(key);
        return true;
    }

    /**
     * 从频道头部取出一个路由条目。
     * 用于提取流程：取出 → 验证 → 有效则提取，源有剩余则 reoffer，无效则丢弃。
     * 同步清理反向索引；调用方若决定保留条目，需调用 {@link #reoffer} 恢复。
     *
     * @param key 频道键
     * @return 头部条目，频道为空返回 null
     */
    public EnderChannelEntry poll(EnderChannelKey key) {
        ChannelData data = channels.get(key);
        if (data == null || data.entries.isEmpty()) return null;

        EnderChannelEntry entry = data.entries.pollFirst();
        if (entry != null) {
            data.routeKeys.remove(RouteKey.of(entry));
            entryToChannel.remove(entry);
            removeFromIndex(entry);
        }
        return entry;
    }

    /**
     * 将条目放回频道尾部（源物品还有剩余时调用）。
     * 恢复 Deque、RouteKey、反向索引和 entryToChannel，使条目继续参与轮询。
     *
     * @param key   频道键
     * @param entry 要放回的条目
     */
    public void reoffer(EnderChannelKey key, EnderChannelEntry entry) {
        ChannelData data = channels.computeIfAbsent(key, k -> new ChannelData());
        data.entries.offerLast(entry);
        data.routeKeys.add(RouteKey.of(entry));
        entryToChannel.put(entry, key);
        addToIndex(entry);
    }

    /**
     * 查看频道头部条目（不移除，用于模拟提取）。
     */
    public EnderChannelEntry peek(EnderChannelKey key) {
        ChannelData data = channels.get(key);
        return data == null || data.entries.isEmpty() ? null : data.entries.peekFirst();
    }

    /**
     * 查看频道路由条目（不移除），支持黑白名单过滤。
     * 遍历频道查找匹配条目，不修改 Deque。
     */
    public EnderChannelEntry peek(EnderChannelKey key, FilterData filterData) {
        return peek(key, filterData, null);
    }

    /**
     * 查看频道路由条目（不移除），支持黑白名单过滤和偏好物品类型。
     *
     * <p>当 {@code preferredItemType} 不为 null 时，优先返回匹配偏好类型的条目；
     * 找不到匹配条目时，回退到正常轮询（头部条目或过滤匹配条目）。</p>
     *
     * <p>这实现了"贪心提取"策略：输出槽已有铁锭时，优先继续提取铁锭（可堆叠），
     * 而不是轮询到金锭导致传输停止。只有铁锭没了或满了，才会切换到金锭。</p>
     *
     * @param key               频道键
     * @param filterData        过滤数据（null 表示不过滤）
     * @param preferredItemType 偏好物品类型（null 表示无偏好，使用正常轮询）
     * @return 匹配的路由条目，频道为空或无匹配返回 null
     */
    public EnderChannelEntry peek(EnderChannelKey key, FilterData filterData, String preferredItemType) {
        ChannelData data = channels.get(key);
        if (data == null || data.entries.isEmpty()) {
            LOGGER.trace("EnderChannelRegistry: peek key={}, empty", key);
            return null;
        }

        if (preferredItemType != null) {
            EnderChannelEntry preferred = findPreferred(data, filterData, preferredItemType);
            if (preferred != null) return preferred;
        }

        if (filterData == null) {
            return data.entries.peekFirst();
        }

        for (EnderChannelEntry entry : data.entries) {
            if (ItemFilterComponent.allowsItemType(filterData, entry.itemType())) {
                return entry;
            }
        }
        LOGGER.trace("EnderChannelRegistry: peek key={}, filter no match, size={}", key, data.entries.size());
        return null;
    }

    /**
     * 在频道数据中查找匹配偏好类型的条目。
     * 优先返回同时满足过滤条件和偏好类型的条目；
     * 找不到时返回 null（由调用方回退到正常轮询）。
     */
    private EnderChannelEntry findPreferred(ChannelData data, FilterData filterData, String preferredItemType) {
        for (EnderChannelEntry entry : data.entries) {
            if (entry.itemType().equals(preferredItemType)) {
                if (filterData == null || ItemFilterComponent.allowsItemType(filterData, entry.itemType())) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * 从频道头部取出一个路由条目，支持偏好物品类型。
     *
     * <p>当 {@code preferredItemType} 不为 null 时，优先取出匹配偏好类型的条目；
     * 找不到时回退到正常头部取出。</p>
     *
     * @param key               频道键
     * @param preferredItemType 偏好物品类型（null 表示无偏好）
     * @return 取出的条目，频道为空返回 null
     */
    public EnderChannelEntry poll(EnderChannelKey key, String preferredItemType) {
        if (preferredItemType == null) {
            return poll(key);
        }

        ChannelData data = channels.get(key);
        if (data == null || data.entries.isEmpty()) return null;

        EnderChannelEntry preferred = null;
        for (EnderChannelEntry entry : data.entries) {
            if (entry.itemType().equals(preferredItemType)) {
                preferred = entry;
                break;
            }
        }

        if (preferred != null) {
            data.entries.remove(preferred);
            data.routeKeys.remove(RouteKey.of(preferred));
            entryToChannel.remove(preferred);
            removeFromIndex(preferred);
            return preferred;
        }

        return poll(key);
    }

    // ═══════════════════════════════════════════════════════════════
    // 移除操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从指定频道移除路由条目。
     */
    public void remove(EnderChannelKey key, EnderChannelEntry entry) {
        ChannelData data = channels.get(key);
        if (data == null) return;
        if (!data.entries.remove(entry)) return;
        data.routeKeys.remove(RouteKey.of(entry));
        entryToChannel.remove(entry);
        removeFromIndex(entry);
        cleanupChannel(key, data);
        syncChannelToAll(key);
    }

    /**
     * 条件移除：收集后删除，迭代期间不修改 Deque，避免 CME。
     */
    private void removeIf(EnderChannelKey key, Predicate<EnderChannelEntry> predicate) {
        ChannelData data = channels.get(key);
        if (data == null) return;

        List<EnderChannelEntry> toRemove = new ArrayList<>();
        for (EnderChannelEntry e : data.entries) {
            if (predicate.test(e)) {
                toRemove.add(e);
            }
        }

        for (EnderChannelEntry e : toRemove) {
            data.entries.remove(e);
            data.routeKeys.remove(RouteKey.of(e));
            entryToChannel.remove(e);
            removeFromIndex(e);
        }

        cleanupChannel(key, data);
        syncChannelToAll(key);
    }

    // ═══════════════════════════════════════════════════════════════
    // 反向索引
    // ═══════════════════════════════════════════════════════════════

    private void addToIndex(EnderChannelEntry entry) {
        if (entry.sourcePos() != null) {
            posIndex.computeIfAbsent(entry.sourcePos(), k -> new ArrayList<>()).add(entry);
        } else if (entry.containerKey() != null) {
            keyIndex.computeIfAbsent(entry.containerKey(), k -> new ArrayList<>()).add(entry);
        }
        if (entry.registrarContainerKey() != null) {
            registrarKeyIndex.computeIfAbsent(entry.registrarContainerKey(), k -> new ArrayList<>()).add(entry);
        }
    }

    private void removeFromIndex(EnderChannelEntry entry) {
        if (entry.sourcePos() != null) {
            List<EnderChannelEntry> list = posIndex.get(entry.sourcePos());
            if (list != null) {
                list.remove(entry);
                if (list.isEmpty()) posIndex.remove(entry.sourcePos());
            }
        } else if (entry.containerKey() != null) {
            List<EnderChannelEntry> list = keyIndex.get(entry.containerKey());
            if (list != null) {
                list.remove(entry);
                if (list.isEmpty()) keyIndex.remove(entry.containerKey());
            }
        }
        if (entry.registrarContainerKey() != null) {
            List<EnderChannelEntry> list = registrarKeyIndex.get(entry.registrarContainerKey());
            if (list != null) {
                list.remove(entry);
                if (list.isEmpty()) registrarKeyIndex.remove(entry.registrarContainerKey());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 按位置清理
    // ═══════════════════════════════════════════════════════════════

    public void removeByPosition(EnderChannelKey key, BlockPos sourcePos) {
        removeIf(key, entry -> entry.sourcePos().equals(sourcePos));
    }

    public void removeByPositionAndSlot(EnderChannelKey key, BlockPos sourcePos, int sourceSlot) {
        removeIf(key, entry ->
            Objects.equals(entry.sourcePos(), sourcePos) && entry.sourceSlot() == sourceSlot);
    }

    public void removeByPositionAndSlot(EnderChannelKey key, String containerKey, int sourceSlot) {
        removeIf(key, entry ->
            containerKey.equals(entry.containerKey()) && entry.sourceSlot() == sourceSlot);
    }

    public void removeByPositionAndSlotFromAllChannels(BlockPos sourcePos, int sourceSlot) {
        removeStaleRoutesInternal(entry ->
            Objects.equals(entry.sourcePos(), sourcePos) && entry.sourceSlot() == sourceSlot);
    }

    public void removeByPositionAndSlotFromAllChannels(String containerKey, int sourceSlot) {
        removeStaleRoutesInternal(entry ->
            containerKey.equals(entry.containerKey()) && entry.sourceSlot() == sourceSlot);
    }

    // ═══════════════════════════════════════════════════════════════
    // 统一路由验证
    // ═══════════════════════════════════════════════════════════════

    /**
     * 验证与指定容器关联的所有路由，移除失效条目。
     *
     * <p>通过反向索引（posIndex/keyIndex/registrarKeyIndex）高效查找相关路由，
     * 一次遍历完成三项检查：</p>
     * <ol>
     *   <li>注册者（活漏斗）是否仍在活跃槽位？</li>
     *   <li>目标（活末影箱）是否仍在活跃槽位，且<b>频道键未发生变化</b>？</li>
     *   <li>源物品是否仍然有效（非空且类型匹配）？</li>
     * </ol>
     *
     * <p><b>检查 2 的频道键比对</b>是 v12 新增的关键校验。玩家拆分/合并活末影箱堆叠会改变
     * 堆叠数，进而改变频道键甚至切换工作模式（专属频道 ↔ 直连）。若不比对频道键，
     * 旧频道下的路由三条检查全部通过，会永久残留在路由表中 —— 玩家日后在同一容器
     * 重新放置同堆叠数的活末影箱时，会意外继承这些遗留路由。</p>
     *
     * @param context           容器上下文，用于定位容器和检查源物品
     * @param activeRegistrarSlots 活跃的活漏斗槽位集合，null 表示跳过注册者检查
     * @param targetKeysBySlot  活跃的活末影箱槽位 → 当前频道键，null 表示跳过目标检查
     * @return 被移除的路由数量
     */
    public int validateRoutes(ContainerContext context,
                              @Nullable Set<Integer> activeRegistrarSlots,
                              @Nullable Map<Integer, EnderChannelKey> targetKeysBySlot) {
        Level level = context.getLevel();
        if (level == null || level.isClientSide) return 0;

        var dim = level.dimension();
        BlockPos pos = context.getBlockPos();
        String containerKey = context.getContainerKey();

        Set<EnderChannelEntry> candidates = new HashSet<>();

        if (pos != null) {
            List<EnderChannelEntry> byPos = posIndex.get(pos);
            if (byPos != null) candidates.addAll(byPos);
        }
        if (containerKey != null) {
            List<EnderChannelEntry> byKey = keyIndex.get(containerKey);
            if (byKey != null) candidates.addAll(byKey);
            List<EnderChannelEntry> byRegistrar = registrarKeyIndex.get(containerKey);
            if (byRegistrar != null) candidates.addAll(byRegistrar);
        }

        if (candidates.isEmpty()) return 0;

        List<EnderChannelEntry> toRemove = new ArrayList<>();
        for (EnderChannelEntry entry : candidates) {
            if (shouldRemoveRoute(entry, context, dim, pos, containerKey,
                    activeRegistrarSlots, targetKeysBySlot)) {
                toRemove.add(entry);
            }
        }

        int removed = 0;
        for (EnderChannelEntry entry : toRemove) {
            EnderChannelKey key = entryToChannel.get(entry);
            if (key != null) {
                remove(key, entry);
                removed++;
            }
        }

        if (removed > 0) {
            LOGGER.debug("EnderChannelRegistry: validateRoutes removed {} stale routes for pos={}, key={}",
                removed, pos, containerKey);
        }
        return removed;
    }

    private boolean shouldRemoveRoute(EnderChannelEntry entry,
                                       ContainerContext context,
                                       ResourceKey<Level> dim,
                                       BlockPos pos,
                                       String containerKey,
                                       @Nullable Set<Integer> activeRegistrarSlots,
                                       @Nullable Map<Integer, EnderChannelKey> targetKeysBySlot) {
        boolean isRegistrar = containerKey != null && containerKey.equals(entry.registrarContainerKey());

        if (isRegistrar && activeRegistrarSlots != null) {
            if (!activeRegistrarSlots.contains(entry.registrarSlot())) {
                return true;
            }
        }

        if (isRegistrar && targetKeysBySlot != null && entry.targetSlot() >= 0) {
            EnderChannelKey currentKey = targetKeysBySlot.get(entry.targetSlot());
            // 末影箱被移走，或其频道键已变化（拆/合堆叠导致模式切换）→ 清理
            if (currentKey == null || !currentKey.equals(entryToChannel.get(entry))) {
                return true;
            }
        }

        boolean isSource = (pos != null && pos.equals(entry.sourcePos()))
                        || (containerKey != null && containerKey.equals(entry.containerKey()));
        if (isSource && entry.sourceDim() != null && entry.sourceDim().equals(dim)) {
            ItemStack sourceStack = context.getItem(entry.sourceSlot());
            if (sourceStack.isEmpty() || !isItemTypeMatch(sourceStack, entry)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 全频道条件清理：收集后删除，迭代期间不修改 Deque。
     */
    private void removeStaleRoutesInternal(Predicate<EnderChannelEntry> shouldRemove) {
        Set<EnderChannelKey> dirty = new HashSet<>();
        List<EnderChannelKey> channelsToRemove = new ArrayList<>();

        for (var channelEntry : channels.entrySet()) {
            EnderChannelKey key = channelEntry.getKey();
            ChannelData data = channelEntry.getValue();
            if (data == null) continue;

            List<EnderChannelEntry> toRemove = new ArrayList<>();
            for (EnderChannelEntry e : data.entries) {
                if (shouldRemove.test(e)) {
                    toRemove.add(e);
                }
            }

            for (EnderChannelEntry e : toRemove) {
                data.entries.remove(e);
                data.routeKeys.remove(RouteKey.of(e));
                entryToChannel.remove(e);
                removeFromIndex(e);
            }

            if (data.entries.isEmpty()) {
                channelsToRemove.add(key);
            } else {
                dirty.add(key);
            }
        }

        for (EnderChannelKey key : channelsToRemove) {
            channels.remove(key);
            dirty.add(key);
        }
        for (EnderChannelKey key : dirty) {
            syncChannelToAll(key);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 区块卸载
    // ═══════════════════════════════════════════════════════════════

    public void onChunkUnload(Level level, ChunkPos chunkPos) {
        var dim = level.dimension();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int chunkMaxX = chunkPos.getMaxBlockX();
        int chunkMaxZ = chunkPos.getMaxBlockZ();

        List<EnderChannelEntry> toRemove = new ArrayList<>();
        Set<EnderChannelKey> dirty = new HashSet<>();

        for (var iter = posIndex.entrySet().iterator(); iter.hasNext(); ) {
            var entry = iter.next();
            BlockPos pos = entry.getKey();
            if (pos.getX() >= chunkMinX && pos.getX() <= chunkMaxX
                && pos.getZ() >= chunkMinZ && pos.getZ() <= chunkMaxZ) {
                for (EnderChannelEntry route : entry.getValue()) {
                    if (dim.equals(route.sourceDim())) {
                        toRemove.add(route);
                    }
                }
            }
        }

        for (var channelEntry : channels.entrySet()) {
            for (EnderChannelEntry route : channelEntry.getValue().entries) {
                String rck = route.registrarContainerKey();
                if (rck == null) continue;
                if (route.sourcePos() != null && isInChunk(route.sourcePos(), chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
                    continue;
                }
                for (BlockPos registrarPos : extractBlockPositions(rck)) {
                    if (isInChunk(registrarPos, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
                        toRemove.add(route);
                        break;
                    }
                }
            }
        }

        for (EnderChannelEntry route : toRemove) {
            EnderChannelKey key = entryToChannel.get(route);
            if (key != null) {
                remove(key, route);
                dirty.add(key);
            }
        }

        for (EnderChannelKey key : dirty) {
            syncChannelToAll(key);
        }

        if (!toRemove.isEmpty()) {
            LOGGER.info("EnderChannelRegistry: chunkUnload dim={}, chunk=({},{}), removed={} entries",
                dim.location(), chunkPos.x, chunkPos.z, toRemove.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    public void clearAll() {
        channels.clear();
        posIndex.clear();
        keyIndex.clear();
        registrarKeyIndex.clear();
        entryToChannel.clear();
        dirtyChannels.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════════

    private boolean isItemTypeMatch(ItemStack stack, EnderChannelEntry entry) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return itemId.equals(entry.itemType());
    }

    private void cleanupChannel(EnderChannelKey key, ChannelData data) {
        if (data.entries.isEmpty()) {
            channels.remove(key);
        }
    }

    private static boolean isInChunk(BlockPos pos, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        return pos.getX() >= chunkMinX && pos.getX() <= chunkMaxX
            && pos.getZ() >= chunkMinZ && pos.getZ() <= chunkMaxZ;
    }

    private static List<BlockPos> extractBlockPositions(String containerKey) {
        List<BlockPos> positions = new ArrayList<>();

        if (containerKey.startsWith("chest_")) {
            String[] parts = containerKey.substring(6).split("_");
            for (int i = 0; i + 2 < parts.length; i += 3) {
                try {
                    int x = Integer.parseInt(parts[i]);
                    int y = Integer.parseInt(parts[i + 1]);
                    int z = Integer.parseInt(parts[i + 2]);
                    positions.add(new BlockPos(x, y, z));
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (containerKey.startsWith("container_")) {
            String[] parts = containerKey.substring(10).split("_");
            if (parts.length >= 3) {
                try {
                    int x = Integer.parseInt(parts[parts.length - 3]);
                    int y = Integer.parseInt(parts[parts.length - 2]);
                    int z = Integer.parseInt(parts[parts.length - 1]);
                    positions.add(new BlockPos(x, y, z));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return positions;
    }
}
