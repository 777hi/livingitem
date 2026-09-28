package com.qiqi.li.living.domain.ender;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活末影箱路由表的客户端镜像缓存 —— 供 Tooltip 渲染读取。
 *
 * <p>频道键由服务端通过 {@code EnderChannelSyncPacket} 下发，客户端按
 * {@link EnderChannelKey} 索引。Tooltip 渲染时客户端可独立算出键
 * （绑定 UUID 存在 DataComponent 中随物品同步，堆叠数即 {@code stack.getCount()}），
 * 因此无需服务端额外告知计算方式。</p>
 *
 * <h3>淘汰策略</h3>
 * <p>频道键空间为「堆叠数 1~64 × (玩家数 + 1)」，长在线客户端会持续累积快照。
 * 因此当服务端下发 {@code channelSize == 0} 时直接移除条目，而不是覆盖成空快照，
 * 避免缓存无限增长。</p>
 */
public class EnderChannelClientCache {

    private static final Map<EnderChannelKey, ChannelSnapshot> cache = new ConcurrentHashMap<>();

    public record EntryDisplay(
        String itemType,
        @Nullable String dimKey,
        @Nullable BlockPos sourcePos,
        int sourceSlot,
        @Nullable String containerKey,
        @Nullable String playerName
    ) {}

    public record ChannelSnapshot(
        int channelSize,
        List<EntryDisplay> entries
    ) {}

    public static final ChannelSnapshot EMPTY = new ChannelSnapshot(0, List.of());

    /**
     * 更新指定频道的快照。
     *
     * <p>{@code channelSize == 0} 视为频道已消失，移除缓存条目而非写入空快照。</p>
     */
    public static void update(EnderChannelKey key, int channelSize, List<EntryDisplay> entries) {
        if (channelSize <= 0) {
            cache.remove(key);
            return;
        }
        cache.put(key, new ChannelSnapshot(channelSize, entries));
    }

    public static ChannelSnapshot getSnapshot(EnderChannelKey key) {
        return cache.getOrDefault(key, EMPTY);
    }

    public static void clear() {
        cache.clear();
    }

    public static void removeChannel(EnderChannelKey key) {
        cache.remove(key);
    }
}
