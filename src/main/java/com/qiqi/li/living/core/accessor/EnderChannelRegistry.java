package com.qiqi.li.living.core.accessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * 活末影箱全局路由表 —— 服务端单例，维护频道→路由条目队列的映射。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>不存储物品，只存储路由指针（物品类型 + 维度 + 位置 + 槽位）</li>
 *   <li>物品始终留在源容器中，由活末影箱的漏斗负责传输</li>
 *   <li>FIFO 公平调度：先注册的路由先被提取</li>
 *   <li>频道隔离：堆叠数 = 频道号，不同堆叠数互不干扰</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 所有操作在服务端 tick 线程中执行，无需额外同步。
 */
public final class EnderChannelRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EnderChannelRegistry INSTANCE = new EnderChannelRegistry();

    private final Map<Integer, Deque<EnderChannelEntry>> channels = new HashMap<>();

    private EnderChannelRegistry() {}

    public static EnderChannelRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 向指定频道注册路由条目（去重：相同位置+槽位+类型的条目不会重复添加）。
     *
     * @param channel 频道号（活末影箱堆叠数）
     * @param entry 路由条目
     * @return true 如果确实新增了条目，false 如果是重复条目已跳过
     */
    public boolean insert(int channel, EnderChannelEntry entry) {
        Deque<EnderChannelEntry> queue = channels.computeIfAbsent(channel, k -> new ArrayDeque<>());

        for (EnderChannelEntry existing : queue) {
            if (Objects.equals(existing.sourcePos(), entry.sourcePos())
                && existing.sourceSlot() == entry.sourceSlot()
                && existing.itemType().equals(entry.itemType())
                && Objects.equals(existing.containerKey(), entry.containerKey())) {
                LOGGER.trace("EnderChannelRegistry: insert duplicate skipped channel={}, item={}, pos={}, slot={}",
                    channel, entry.itemType(), entry.sourcePos(), entry.sourceSlot());
                return false;
            }
        }

        queue.addLast(entry);
        LOGGER.trace("EnderChannelRegistry: insert channel={}, item={}, pos={}, slot={}, size={}",
            channel, entry.itemType(), entry.sourcePos(), entry.sourceSlot(), getChannelSize(channel));
        return true;
    }

    /**
     * 查看频道头部的路由条目（不移除）。
     *
     * @param channel 频道号
     * @param filterState 过滤状态（用于白名单匹配，可为 null）
     * @return 匹配的路由条目，如果频道为空或无匹配条目返回 null
     */
    public EnderChannelEntry peek(int channel, ComponentState filterState) {
        Deque<EnderChannelEntry> queue = channels.get(channel);
        if (queue == null || queue.isEmpty()) {
            LOGGER.trace("EnderChannelRegistry: peek channel={}, queue empty", channel);
            return null;
        }

        if (filterState == null) {
            EnderChannelEntry entry = queue.peekFirst();
            LOGGER.trace("EnderChannelRegistry: peek channel={}, no filter, found={}",
                channel, entry != null ? entry.itemType() : "null");
            return entry;
        }

        for (EnderChannelEntry entry : queue) {
            if (ItemFilterComponent.allowsItemType(filterState, entry.itemType())) {
                LOGGER.trace("EnderChannelRegistry: peek channel={}, filter matched item={}",
                    channel, entry.itemType());
                return entry;
            }
        }
        LOGGER.trace("EnderChannelRegistry: peek channel={}, filter no match, size={}",
            channel, queue.size());
        return null;
    }

    /**
     * 弹出频道头部的路由条目（移除）。
     *
     * @param channel 频道号
     * @return 被移除的条目，如果频道为空返回 null
     */
    public EnderChannelEntry pop(int channel) {
        Deque<EnderChannelEntry> queue = channels.get(channel);
        if (queue == null || queue.isEmpty()) return null;
        EnderChannelEntry entry = queue.pollFirst();
        if (queue.isEmpty()) channels.remove(channel);
        LOGGER.trace("EnderChannelRegistry: pop channel={}, item={}, remaining={}",
            channel, entry.itemType(), getChannelSize(channel));
        return entry;
    }

    /**
     * 移除指定路由条目。
     *
     * @param channel 频道号
     * @param entry 要移除的条目
     */
    public void remove(int channel, EnderChannelEntry entry) {
        Deque<EnderChannelEntry> queue = channels.get(channel);
        if (queue != null) {
            queue.remove(entry);
            if (queue.isEmpty()) {
                channels.remove(channel);
            }
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
        Deque<EnderChannelEntry> queue = channels.get(channel);
        if (queue != null) {
            queue.removeIf(entry -> entry.sourcePos().equals(sourcePos));
            if (queue.isEmpty()) {
                channels.remove(channel);
            }
        }
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
        Deque<EnderChannelEntry> queue = channels.get(channel);
        if (queue != null) {
            queue.removeIf(entry -> Objects.equals(entry.sourcePos(), sourcePos) && entry.sourceSlot() == sourceSlot);
            if (queue.isEmpty()) {
                channels.remove(channel);
            }
        }
    }

    /**
     * 移除指定频道中指定容器+槽位的路由条目（玩家背包版本）。
     *
     * @param channel 频道号
     * @param containerKey 容器唯一标识 key
     * @param sourceSlot 源槽位
     */
    public void removeByPositionAndSlot(int channel, String containerKey, int sourceSlot) {
        Deque<EnderChannelEntry> queue = channels.get(channel);
        if (queue != null) {
            queue.removeIf(entry -> containerKey.equals(entry.containerKey()) && entry.sourceSlot() == sourceSlot);
            if (queue.isEmpty()) {
                channels.remove(channel);
            }
        }
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

    private void removeStaleRoutesInternal(java.util.function.Predicate<EnderChannelEntry> shouldRemove) {
        List<Integer> channelsToRemove = new ArrayList<>();
        for (var entry : channels.entrySet()) {
            Deque<EnderChannelEntry> queue = entry.getValue();
            if (queue == null) continue;
            queue.removeIf(shouldRemove);
            if (queue.isEmpty()) {
                channelsToRemove.add(entry.getKey());
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

        int totalBefore = channels.values().stream().mapToInt(Deque::size).sum();
        for (Deque<EnderChannelEntry> queue : channels.values()) {
            queue.removeIf(entry -> {
                if (!entry.sourceDim().equals(dim)) return false;
                BlockPos pos = entry.sourcePos();
                if (pos == null) return false;
                return pos.getX() >= chunkMinX && pos.getX() <= chunkMaxX
                    && pos.getZ() >= chunkMinZ && pos.getZ() <= chunkMaxZ;
            });
        }
        channels.entrySet().removeIf(e -> e.getValue().isEmpty());
        int totalAfter = channels.values().stream().mapToInt(Deque::size).sum();

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
        Deque<EnderChannelEntry> queue = channels.get(channel);
        return queue == null ? 0 : queue.size();
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
}