package com.qiqi.li.living.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * <b>服务端「所有维度同时持有」的跨 tick 状态容器</b> —— 只允许按维度访问。
 *
 * <h3>为什么存在</h3>
 * <p>{@code LivingItem.onServerTick} 的同步器 {@code flush(ServerLevel)} 是<b>逐维度</b>调用的，
 * 但跨 tick 的动作状态必须同时保留所有维度的桶。用一张全服单表会出两种错：
 * ① 先 flush 的维度抽走别的维度的登记；② 拿 A 维度的状态在 B 维度里按坐标执行动作。
 * 本类把「按维度分桶」变成唯一可表达的形态 —— 不提供任何不带维度的读写入口。</p>
 *
 * <p>⚠️ 客户端「当前维度 + 整表替换」的缓存是另一套语义，见 {@link DimensionScopedCache}，
 * 不可互相替代。</p>
 *
 * <h3>清理</h3>
 * <p>本类只是存储，不持有 static 字段。若实例放在 {@code static} 字段里，<b>持有者</b>必须
 * 在自己的 {@code static {}} 块登记 {@link StaticCacheRegistry#onServerStop}，与 {@code clear()}
 * 同文件（见 {@code StaticCacheRegistry} 类注释「登记位置」）。</p>
 *
 * <p>⚠️ 仅主线程访问，不做同步。</p>
 */
public final class PerLevelCache<K, V> {

    private final Map<ResourceLocation, Map<K, V>> byLevel = new HashMap<>();

    /** 本维度的可写桶（不存在则新建）。用于登记 / 计数等写路径。 */
    public Map<K, V> bucket(ResourceLocation level) {
        return byLevel.computeIfAbsent(level, k -> new LinkedHashMap<>());
    }

    /**
     * 本维度的<b>只读</b>视图；不存在返回空表。
     *
     * <p>⚠️ 需要写时用 {@link #bucket(ResourceLocation)}，不要对返回值调用写方法。</p>
     */
    public Map<K, V> peek(ResourceLocation level) {
        Map<K, V> bucket = byLevel.get(level);
        return bucket == null ? Map.of() : Collections.unmodifiableMap(bucket);
    }

    /**
     * 取走本维度的桶 —— 从缓存移除，返回的桶已脱离缓存，可安全读写。
     * 不存在返回空表。
     */
    public Map<K, V> take(ResourceLocation level) {
        Map<K, V> bucket = byLevel.remove(level);
        return bucket == null ? Map.of() : bucket;
    }

    /** 整体替换本维度的桶；空表 = 移除该维度（不留空桶）。 */
    public void replace(ResourceLocation level, Map<K, V> values) {
        if (values.isEmpty()) {
            byLevel.remove(level);
        } else {
            byLevel.put(level, new LinkedHashMap<>(values));
        }
    }

    /** 本维度桶为空时移除（空桶不积残留）。 */
    public void dropIfEmpty(ResourceLocation level) {
        Map<K, V> bucket = byLevel.get(level);
        if (bucket != null && bucket.isEmpty()) {
            byLevel.remove(level);
        }
    }

    /** 清空全部维度（服务端关闭时由持有者调用）。 */
    public void clear() {
        byLevel.clear();
    }
}
