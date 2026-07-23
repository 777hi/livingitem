package com.qiqi.li.living.core.accessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * 活末影箱全局路由表 —— 服务端单例，维护频道→路由条目列表的映射。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>不存储物品，只存储路由指针（物品类型 + 维度 + 位置 + 槽位）</li>
 *   <li>物品始终留在源容器中，由活末影箱的漏斗负责传输</li>
 *   <li>轮询公平调度：用 nextIndex 指针轮流取，每个 push 端机会均等</li>
 *   <li>频道隔离：堆叠数 = 频道号，不同堆叠数互不干扰</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 所有操作在服务端 tick 线程中执行，无需额外同步。
 */
public final class EnderChannelRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EnderChannelRegistry INSTANCE = new EnderChannelRegistry();

    private static class ChannelData {
        final List<EnderChannelEntry> entries = new ArrayList<>();
        int nextIndex;
    }

    private final Map<Integer, ChannelData> channels = new HashMap<>();

    private EnderChannelRegistry() {}

    public static EnderChannelRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 检查指定频道中是否已存在匹配的路由条目。
     * 匹配规则：位置+槽位+物品类型+容器key 完全一致。
     * 用于 registerRoute 的前置检查，避免每次 tick 都执行 delete+insert。
     *
     * @param channel 频道号
     * @param entry 路由条目
     * @return true 如果当前频道已存在相同条目
     */
    public boolean contains(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.get(channel);
        if (data == null) return false;
        for (EnderChannelEntry existing : data.entries) {
            if (Objects.equals(existing.sourcePos(), entry.sourcePos())
                && existing.sourceSlot() == entry.sourceSlot()
                && existing.itemType().equals(entry.itemType())
                && Objects.equals(existing.containerKey(), entry.containerKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向指定频道注册路由条目（去重：相同位置+槽位+类型的条目不会重复添加）。
     *
     * @param channel 频道号（活末影箱堆叠数）
     * @param entry 路由条目
     * @return true 如果确实新增了条目，false 如果是重复条目已跳过
     */
    public boolean insert(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.computeIfAbsent(channel, k -> new ChannelData());

        for (EnderChannelEntry existing : data.entries) {
            if (Objects.equals(existing.sourcePos(), entry.sourcePos())
                && existing.sourceSlot() == entry.sourceSlot()
                && existing.itemType().equals(entry.itemType())
                && Objects.equals(existing.containerKey(), entry.containerKey())) {
                LOGGER.trace("EnderChannelRegistry: insert duplicate skipped channel={}, item={}, pos={}, slot={}",
                    channel, entry.itemType(), entry.sourcePos(), entry.sourceSlot());
                return false;
            }
        }

        data.entries.add(entry);
        LOGGER.trace("EnderChannelRegistry: insert channel={}, item={}, pos={}, slot={}, size={}",
            channel, entry.itemType(), entry.sourcePos(), entry.sourceSlot(), data.entries.size());
        return true;
    }

    /**
     * 查看频道路由条目（不移除），采用轮询策略保证公平。
     *
     * <p>用 nextIndex 指针轮流取，每次取完指针后移。多 pull 端场景下
     * 每个 push 端的物品都有机会被提取，不会出现排在前面的条目被反复提取
     * 而排在后面的条目永远拿不到的情况。</p>
     *
     * @param channel 频道号
     * @param filterState 过滤状态（用于白名单匹配，可为 null）
     * @return 匹配的路由条目，如果频道为空或无匹配条目返回 null
     */
    public EnderChannelEntry peek(int channel, ComponentState filterState) {
        ChannelData data = channels.get(channel);
        if (data == null || data.entries.isEmpty()) {
            LOGGER.trace("EnderChannelRegistry: peek channel={}, empty", channel);
            return null;
        }

        List<EnderChannelEntry> list = data.entries;
        int size = list.size();
        data.nextIndex %= size;

        if (filterState == null) {
            EnderChannelEntry entry = list.get(data.nextIndex);
            data.nextIndex = (data.nextIndex + 1) % size;
            LOGGER.trace("EnderChannelRegistry: peek channel={}, no filter, index={} → {}",
                channel, (data.nextIndex - 1 + size) % size, entry.itemType());
            return entry;
        }

        for (int i = 0; i < size; i++) {
            int idx = (data.nextIndex + i) % size;
            EnderChannelEntry entry = list.get(idx);
            if (ItemFilterComponent.allowsItemType(filterState, entry.itemType())) {
                data.nextIndex = (idx + 1) % size;
                LOGGER.trace("EnderChannelRegistry: peek channel={}, filter matched, index={} → {}",
                    channel, idx, entry.itemType());
                return entry;
            }
        }
        LOGGER.trace("EnderChannelRegistry: peek channel={}, filter no match, size={}",
            channel, size);
        return null;
    }

    /**
     * 移除指定路由条目。
     *
     * @param channel 频道号
     * @param entry 要移除的条目
     */
    public void remove(int channel, EnderChannelEntry entry) {
        ChannelData data = channels.get(channel);
        if (data == null) return;
        int idx = data.entries.indexOf(entry);
        if (idx < 0) return;
        data.entries.remove(idx);
        if (idx < data.nextIndex) {
            data.nextIndex--;
        }
        if (data.entries.isEmpty()) {
            channels.remove(channel);
        }
    }

    /**
     * 移除指定频道中与指定位置相关的所有路由条目。
     * 在区块卸载时调用，清理失效路由。
     *
     * @param channel 频道号
     * @param sourcePos 源容器位置
     */
    public void removeByPosition(int channel, BlockPos sourcePos) {
        ChannelData data = channels.get(channel);
        if (data == null) return;
        data.entries.removeIf(entry -> entry.sourcePos().equals(sourcePos));
        cleanupChannel(channel, data);
    }

    /**
     * 移除指定频道中指定位置+槽位的路由条目。
     * 当源槽位物品变化或活漏斗被移走时调用，清理旧路由。
     *
     * @param channel 频道号
     * @param sourcePos 源容器位置
     * @param sourceSlot 源槽位
     */
    public void removeByPositionAndSlot(int channel, BlockPos sourcePos, int sourceSlot) {
        ChannelData data = channels.get(channel);
        if (data == null) return;
        data.entries.removeIf(entry ->
            Objects.equals(entry.sourcePos(), sourcePos) && entry.sourceSlot() == sourceSlot);
        cleanupChannel(channel, data);
    }

    /**
     * 移除所有频道中指定位置+槽位的路由条目。
     * 当活末影箱频道改变时调用，清理旧频道中的残留路由。
     *
     * @param sourcePos 源容器位置
     * @param sourceSlot 源槽位
     */
    public void removeByPositionAndSlotFromAllChannels(BlockPos sourcePos, int sourceSlot) {
        removeStaleRoutesInternal(entry ->
            Objects.equals(entry.sourcePos(), sourcePos) && entry.sourceSlot() == sourceSlot);
    }

    /**
     * 移除所有频道中指定容器+槽位的路由条目（玩家背包版本）。
     *
     * @param containerKey 容器唯一标识 key
     * @param sourceSlot 源槽位
     */
    public void removeByPositionAndSlotFromAllChannels(String containerKey, int sourceSlot) {
        removeStaleRoutesInternal(entry ->
            containerKey.equals(entry.containerKey()) && entry.sourceSlot() == sourceSlot);
    }

    /**
     * 移除指定频道中指定容器+槽位的路由条目（玩家背包版本）。
     *
     * @param channel 频道号
     * @param containerKey 容器唯一标识 key
     * @param sourceSlot 源槽位
     */
    public void removeByPositionAndSlot(int channel, String containerKey, int sourceSlot) {
        ChannelData data = channels.get(channel);
        if (data == null) return;
        data.entries.removeIf(entry ->
            containerKey.equals(entry.containerKey()) && entry.sourceSlot() == sourceSlot);
        cleanupChannel(channel, data);
    }

    /**
     * 清理指定容器中已不存在活漏斗的路由条目。
     *
     * <p>当活漏斗被从容器中移走时，该漏斗注册的所有路由都应被清理。
     * 遍历所有频道，移除 sourcePos 匹配且 registrarSlot 不在 activeSlots 中的路由。</p>
     *
     * @param sourcePos 源容器位置
     * @param activeRegistrarSlots 当前容器中活漏斗所在的槽位集合
     */
    public void removeStaleRoutes(BlockPos sourcePos, Set<Integer> activeRegistrarSlots) {
        removeStaleRoutesInternal(route ->
            Objects.equals(route.sourcePos(), sourcePos)
            && !activeRegistrarSlots.contains(route.registrarSlot()));
    }

    /**
     * 清理指定容器中已不存在活漏斗的路由条目（玩家背包版本）。
     *
     * @param containerKey 容器唯一标识 key
     * @param activeRegistrarSlots 当前容器中活漏斗所在的槽位集合
     */
    public void removeStaleRoutes(String containerKey, Set<Integer> activeRegistrarSlots) {
        removeStaleRoutesInternal(route ->
            containerKey.equals(route.containerKey())
            && !activeRegistrarSlots.contains(route.registrarSlot()));
    }

    /**
     * 清理活末影箱被移走后的路由条目。
     *
     * <p>当路由条目关联了 targetSlot（活末影箱所在槽位），
     * 但该槽位已不再是活末影箱时，移除该路由。</p>
     *
     * @param activeEnderChestSlots 当前容器中活末影箱所在的槽位集合
     */
    public void removeStaleEnderChestRoutes(Set<Integer> activeEnderChestSlots) {
        removeStaleRoutesInternal(route ->
            route.targetSlot() >= 0 && !activeEnderChestSlots.contains(route.targetSlot()));
    }

    private void removeStaleRoutesInternal(java.util.function.Predicate<EnderChannelEntry> shouldRemove) {
        List<Integer> channelsToRemove = new ArrayList<>();
        for (var entry : channels.entrySet()) {
            ChannelData data = entry.getValue();
            if (data == null) continue;
            data.entries.removeIf(shouldRemove);
            if (data.entries.isEmpty()) {
                channelsToRemove.add(entry.getKey());
            } else {
                data.nextIndex %= data.entries.size();
            }
        }
        for (int channel : channelsToRemove) {
            channels.remove(channel);
        }
    }

    /**
     * 当区块卸载时，清理该区块中所有源容器的路由条目。
     *
     * @param level 区块所在世界
     * @param chunkPos 区块坐标
     */
    public void onChunkUnload(Level level, ChunkPos chunkPos) {
        var dim = level.dimension();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int chunkMaxX = chunkPos.getMaxBlockX();
        int chunkMaxZ = chunkPos.getMaxBlockZ();

        int totalBefore = channels.values().stream().mapToInt(d -> d.entries.size()).sum();
        for (ChannelData data : channels.values()) {
            data.entries.removeIf(entry -> {
                if (!entry.sourceDim().equals(dim)) return false;
                BlockPos pos = entry.sourcePos();
                if (pos == null) return false;
                return pos.getX() >= chunkMinX && pos.getX() <= chunkMaxX
                    && pos.getZ() >= chunkMinZ && pos.getZ() <= chunkMaxZ;
            });
        }
        channels.entrySet().removeIf(e -> {
            if (e.getValue().entries.isEmpty()) return true;
            e.getValue().nextIndex %= e.getValue().entries.size();
            return false;
        });
        int totalAfter = channels.values().stream().mapToInt(d -> d.entries.size()).sum();

        int removed = totalBefore - totalAfter;
        if (removed > 0) {
            LOGGER.info("EnderChannelRegistry: chunkUnload dim={}, chunk=({},{}), removed={} entries",
                dim.location(), chunkPos.x, chunkPos.z, removed);
        }
    }

    /**
     * 获取频道中的路由条目数量（用于调试）。
     */
    public int getChannelSize(int channel) {
        ChannelData data = channels.get(channel);
        return data == null ? 0 : data.entries.size();
    }

    /**
     * 获取所有活跃频道数量（用于调试）。
     */
    public int getActiveChannelCount() {
        return channels.size();
    }

    /**
     * 清空所有路由表（用于调试命令或服务器重置）。
     */
    public void clearAll() {
        channels.clear();
    }

    private void cleanupChannel(int channel, ChannelData data) {
        if (data.entries.isEmpty()) {
            channels.remove(channel);
        } else {
            data.nextIndex %= data.entries.size();
        }
    }
}