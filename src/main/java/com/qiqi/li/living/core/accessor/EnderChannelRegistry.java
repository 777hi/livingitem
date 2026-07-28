package com.qiqi.li.living.core.accessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.network.EnderChannelSyncPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * 活末影箱全局路由表 —— 服务端单例，维护频道→路由条目双端队列的映射。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>不存储物品，只存储路由指针（物品类型 + 维度 + 位置 + 槽位）</li>
 *   <li>物品始终留在源容器中，由活末影箱的漏斗负责传输</li>
 *   <li>轮询公平调度：用 {@link Deque} 的 poll/reoffer 天然实现循环，
 *       取出头部 → 验证 → 有效则提取，源还有物品则放回尾部</li>
 *   <li>频道隔离：堆叠数 = 频道号，不同堆叠数互不干扰</li>
 * </ul>
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

    private final Map<Integer, ChannelData> channels = new HashMap<>();

    /** 反向索引：方块位置 → 路由条目列表 */
    private final Map<BlockPos, List<EnderChannelEntry>> posIndex = new HashMap<>();

    /** 反向索引：容器 key → 路由条目列表 */
    private final Map<String, List<EnderChannelEntry>> keyIndex = new HashMap<>();

    /** 条目→频道反向索引：用于 O(1) 查找条目所属频道 */
    private final Map<EnderChannelEntry, Integer> entryToChannel = new HashMap<>();

    /** 延迟同步：本 tick 内被修改的频道集合，tick 末尾统一同步 */
    private final Set<Integer> dirtyChannels = new HashSet<>();

    private EnderChannelRegistry() {}

    public static EnderChannelRegistry getInstance() {
        return INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    private void syncChannelToAll(int channel) {
        dirtyChannels.add(channel);
    }

    public void flushDirtyChannels() {
        if (dirtyChannels.isEmpty()) return;
        for (int channel : dirtyChannels) {
            ChannelData data = channels.get(channel);
            int channelSize = data == null ? 0 : data.entries.size();
            int totalRoutes = getTotalRouteCount();
            List<EnderChannelEntry> entryList = data == null ? List.of() : List.copyOf(data.entries);

            EnderChannelSyncPacket packet = EnderChannelSyncPacket.fromRegistry(
                channel, channelSize, totalRoutes, entryList);

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
    public boolean contains(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.get(channel);
        return data != null && data.routeKeys.contains(RouteKey.of(entry));
    }

    /**
     * 向指定频道注册路由条目（去重：O(1) 通过 RouteKey 集合）。
     *
     * @param channel 频道号
     * @param entry 路由条目
     * @return true 如果确实新增了条目
     */
    public boolean offer(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.computeIfAbsent(channel, k -> new ChannelData());
        RouteKey key = RouteKey.of(entry);

        if (!data.routeKeys.add(key)) {
            LOGGER.trace("EnderChannelRegistry: offer duplicate skipped channel={}, item={}, pos={}, slot={}",
                channel, entry.itemType(), entry.sourcePos(), entry.sourceSlot());
            return false;
        }

        data.entries.offerLast(entry);
        entryToChannel.put(entry, channel);
        addToIndex(entry);
        LOGGER.trace("EnderChannelRegistry: offer channel={}, item={}, pos={}, slot={}, size={}",
            channel, entry.itemType(), entry.sourcePos(), entry.sourceSlot(), data.entries.size());
        syncChannelToAll(channel);
        return true;
    }

    /**
     * 从频道头部取出一个路由条目。
     * 用于提取流程：取出 → 验证 → 有效则提取，源有剩余则 reoffer，无效则丢弃。
     * 同步清理反向索引；调用方若决定保留条目，需调用 {@link #reoffer} 恢复。
     *
     * @param channel 频道号
     * @return 头部条目，频道为空返回 null
     */
    public EnderChannelEntry poll(int channel) {
        ChannelData data = channels.get(channel);
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
     * @param channel 频道号
     * @param entry 要放回的条目
     */
    public void reoffer(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.computeIfAbsent(channel, k -> new ChannelData());
        data.entries.offerLast(entry);
        data.routeKeys.add(RouteKey.of(entry));
        entryToChannel.put(entry, channel);
        addToIndex(entry);
    }

    /**
     * 查看频道头部条目（不移除，用于模拟提取）。
     */
    public EnderChannelEntry peek(int channel) {
        ChannelData data = channels.get(channel);
        return data == null || data.entries.isEmpty() ? null : data.entries.peekFirst();
    }

    /**
     * 查看频道路由条目（不移除），支持黑白名单过滤。
     * 遍历频道查找匹配条目，不修改 Deque。
     */
    public EnderChannelEntry peek(int channel, FilterData filterData) {
        ChannelData data = channels.get(channel);
        if (data == null || data.entries.isEmpty()) {
            LOGGER.trace("EnderChannelRegistry: peek channel={}, empty", channel);
            return null;
        }

        if (filterData == null) {
            return data.entries.peekFirst();
        }

        for (EnderChannelEntry entry : data.entries) {
            if (ItemFilterComponent.allowsItemType(filterData, entry.itemType())) {
                return entry;
            }
        }
        LOGGER.trace("EnderChannelRegistry: peek channel={}, filter no match, size={}",
            channel, data.entries.size());
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // 移除操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从指定频道移除路由条目。
     */
    public void remove(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.get(channel);
        if (data == null) return;
        if (!data.entries.remove(entry)) return;
        data.routeKeys.remove(RouteKey.of(entry));
        entryToChannel.remove(entry);
        removeFromIndex(entry);
        cleanupChannel(channel, data);
        syncChannelToAll(channel);
    }

    /**
     * 条件移除：收集后删除，迭代期间不修改 Deque，避免 CME。
     */
    private void removeIf(int channel, Predicate<EnderChannelEntry> predicate) {
        ChannelData data = channels.get(channel);
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

        cleanupChannel(channel, data);
        syncChannelToAll(channel);
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
    }

    // ═══════════════════════════════════════════════════════════════
    // 按位置清理
    // ═══════════════════════════════════════════════════════════════

    public void removeByPosition(int channel, BlockPos sourcePos) {
        removeIf(channel, entry -> entry.sourcePos().equals(sourcePos));
    }

    public void removeByPositionAndSlot(int channel, BlockPos sourcePos, int sourceSlot) {
        removeIf(channel, entry ->
            Objects.equals(entry.sourcePos(), sourcePos) && entry.sourceSlot() == sourceSlot);
    }

    public void removeByPositionAndSlot(int channel, String containerKey, int sourceSlot) {
        removeIf(channel, entry ->
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
    // 失效路由清理
    // ═══════════════════════════════════════════════════════════════

    public void removeStaleRoutes(BlockPos sourcePos, Set<Integer> activeRegistrarSlots) {
        removeStaleRoutesInternal(route ->
            Objects.equals(route.sourcePos(), sourcePos)
            && !activeRegistrarSlots.contains(route.registrarSlot()));
    }

    public void removeStaleRoutes(String containerKey, Set<Integer> activeRegistrarSlots) {
        removeStaleRoutesInternal(route ->
            containerKey.equals(route.containerKey())
            && !activeRegistrarSlots.contains(route.registrarSlot()));
    }

    public void removeStaleEnderChestRoutes(String containerKey, Set<Integer> activeEnderChestSlots) {
        removeStaleRoutesInternal(route ->
            containerKey.equals(route.registrarContainerKey())
            && route.targetSlot() >= 0 && !activeEnderChestSlots.contains(route.targetSlot()));
    }

    public void removeStaleRoutesByRegistrarKey(String registrarContainerKey, Set<Integer> activeRegistrarSlots) {
        removeStaleRoutesInternal(route ->
            registrarContainerKey.equals(route.registrarContainerKey())
            && !activeRegistrarSlots.contains(route.registrarSlot()));
    }

    /**
     * 全频道条件清理：收集后删除，迭代期间不修改 Deque。
     */
    private void removeStaleRoutesInternal(Predicate<EnderChannelEntry> shouldRemove) {
        Set<Integer> dirty = new HashSet<>();
        List<Integer> channelsToRemove = new ArrayList<>();

        for (var channelEntry : channels.entrySet()) {
            int ch = channelEntry.getKey();
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
                channelsToRemove.add(ch);
            } else {
                dirty.add(ch);
            }
        }

        for (int ch : channelsToRemove) {
            channels.remove(ch);
            dirty.add(ch);
        }
        for (int ch : dirty) {
            syncChannelToAll(ch);
        }
    }

    /**
     * 清理源物品已被移走或替换的路由（使用反向索引，O(相关路由)）。
     */
    public int cleanStaleSourceRoutes(ContainerContext context) {
        Level level = context.getLevel();
        if (level == null || level.isClientSide) return 0;

        var dim = level.dimension();
        BlockPos pos = context.getBlockPos();
        String containerKey = context.getContainerKey();
        int cleaned = 0;

        List<EnderChannelEntry> relevantEntries;
        if (pos != null) {
            relevantEntries = posIndex.get(pos);
        } else if (containerKey != null) {
            relevantEntries = keyIndex.get(containerKey);
        } else {
            return 0;
        }

        if (relevantEntries == null || relevantEntries.isEmpty()) return 0;

        Set<Integer> dirty = new HashSet<>();
        for (int i = relevantEntries.size() - 1; i >= 0; i--) {
            EnderChannelEntry entry = relevantEntries.get(i);
            if (entry.sourceDim() != null && !entry.sourceDim().equals(dim)) continue;

            ItemStack sourceStack = context.getItem(entry.sourceSlot());
            if (sourceStack.isEmpty() || !isItemTypeMatch(sourceStack, entry)) {
                Integer ch = entryToChannel.get(entry);
                if (ch != null) {
                    remove(ch, entry);
                    dirty.add(ch);
                    cleaned++;
                }
            }
        }

        for (int ch : dirty) {
            syncChannelToAll(ch);
        }

        if (cleaned > 0) {
            LOGGER.debug("EnderChannelRegistry: cleaned {} stale source routes for pos={}, key={}",
                cleaned, pos, containerKey);
        }
        return cleaned;
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
        Set<Integer> dirty = new HashSet<>();

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
            Integer ch = entryToChannel.get(route);
            if (ch != null) {
                remove(ch, route);
                dirty.add(ch);
            }
        }

        for (int ch : dirty) {
            syncChannelToAll(ch);
        }

        if (!toRemove.isEmpty()) {
            LOGGER.info("EnderChannelRegistry: chunkUnload dim={}, chunk=({},{}), removed={} entries",
                dim.location(), chunkPos.x, chunkPos.z, toRemove.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════════

    public int getChannelSize(int channel) {
        ChannelData data = channels.get(channel);
        return data == null ? 0 : data.entries.size();
    }

    public List<EnderChannelEntry> getEntries(int channel) {
        ChannelData data = channels.get(channel);
        if (data == null) return List.of();
        return List.copyOf(data.entries);
    }

    public Set<Integer> getChannels() {
        return new HashSet<>(channels.keySet());
    }

    public int getActiveChannelCount() {
        return channels.size();
    }

    public int getTotalRouteCount() {
        int total = 0;
        for (ChannelData data : channels.values()) {
            total += data.entries.size();
        }
        return total;
    }

    public ChannelSnapshot getChannelSnapshot(int channel) {
        ChannelData data = channels.get(channel);
        int channelSize = data == null ? 0 : data.entries.size();
        int totalRoutes = getTotalRouteCount();
        List<EnderChannelEntry> entries = data == null ? List.of() : List.copyOf(data.entries);
        return new ChannelSnapshot(channelSize, totalRoutes, entries);
    }

    public record ChannelSnapshot(int channelSize, int totalRoutes, List<EnderChannelEntry> entries) {}

    public void clearAll() {
        channels.clear();
        posIndex.clear();
        keyIndex.clear();
        entryToChannel.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════════

    private boolean isItemTypeMatch(ItemStack stack, EnderChannelEntry entry) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return itemId.equals(entry.itemType());
    }

    private void cleanupChannel(int channel, ChannelData data) {
        if (data.entries.isEmpty()) {
            channels.remove(channel);
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