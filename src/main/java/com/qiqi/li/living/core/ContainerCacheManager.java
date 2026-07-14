package com.qiqi.li.living.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.living.core.components.DirectionModeComponent;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * 容器槽位映射缓存管理器
 * 
 * 设计理念：
 * 1. 按需计算：只在第一次访问时计算映射，后续复用
 * 2. 容器指纹：通过容器特征生成唯一标识，避免重复计算
 * 3. 弱引用缓存：允许GC回收不再使用的缓存条目
 * 4. 自动失效：检测容器变化时自动清除相关缓存
 */
public final class ContainerCacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCacheManager.class);

    private static final ContainerCacheManager INSTANCE = new ContainerCacheManager();

    public static ContainerCacheManager getInstance() {
        return INSTANCE;
    }

    private final Int2ObjectMap<ContainerSlotMapping> cache = new Int2ObjectOpenHashMap<>();
    
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long evictions = 0;

    private ContainerCacheManager() {}

    /**
     * 获取或创建指定容器的槽位映射
     * 
     * @param container 目标容器
     * @param config 活物品配置
     * @return 槽位映射对象，如果容器无效则返回null
     */
    @Nullable
    public ContainerSlotMapping getOrCreateMapping(Container container, LivingFunctionConfig config) {
        if (container == null || config == null) {
            return null;
        }

        int containerSize = safeGetContainerSize(container);
        if (containerSize <= 0) {
            return null;
        }

        String fingerprint = generateFingerprint(container, containerSize, config);

        int cacheKey = fingerprint.hashCode();

        synchronized (cache) {
            ContainerSlotMapping existing = cache.get(cacheKey);
            if (existing != null && existing.isValidFor(container)) {
                cacheHits++;
                return existing;
            }
        }

        cacheMisses++;
        
        ContainerSlotMapping mapping = computeMapping(container, containerSize, config);
        
        if (mapping != null) {
            synchronized (cache) {
                if (cache.size() > 1000) {
                    evictOldestEntries(100);  // 防止内存泄漏
                    evictions += 100;
                }
                cache.put(cacheKey, mapping);
            }
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Created slot mapping for container(size={}, fingerprint={})", 
                           containerSize, fingerprint);
            }
        }

        return mapping;
    }

    /**
     * 安全获取容器大小（带异常保护）
     */
    private int safeGetContainerSize(Container container) {
        try {
            int size = container.getContainerSize();
            return size > 0 && size <= 256 ? size : -1;  // 合理性检查
        } catch (Exception e) {
            LOGGER.warn("Failed to get container size", e);
            return -1;
        }
    }

    /**
     * 生成容器指纹（用于缓存键）
     * 
     * 指纹包含：
     * - 容器大小
     * - 容器类名（区分不同类型的容器）
     * - 最大堆叠数（某些容器可能不同）
     */
    private String generateFingerprint(Container container, int size, LivingFunctionConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("size=").append(size)
          .append("|type=").append(container.getClass().getSimpleName())
          .append("|maxStack=").append(container.getMaxStackSize());

        com.qiqi.li.living.core.components.ILivingComponent rawComp = config.getConfiguredInstance(DirectionModeComponent.class);
        if (rawComp instanceof DirectionModeComponent dmc) {
            sb.append("|mode=").append(dmc.getMode());
            if (dmc.getMode() == DirectionModeComponent.DirectionMode.SLOTS) {
                Map<String, com.qiqi.li.living.core.model.Pos2D> slots = dmc.getDefaultSlots();
                for (var entry : slots.entrySet()) {
                    sb.append("|").append(entry.getKey()).append("=")
                      .append(entry.getValue().x()).append(",").append(entry.getValue().y());
                }
            }
        }
        
        return sb.toString();
    }

    /**
     * 计算完整的槽位映射表
     */
    private ContainerSlotMapping computeMapping(Container container, int size, LivingFunctionConfig config) {
        Int2ObjectMap<int[]> slotMappings = new Int2ObjectOpenHashMap<>();

        DirectionModeComponent dirComp = null;
        com.qiqi.li.living.core.components.ILivingComponent rawComp = config.getConfiguredInstance(DirectionModeComponent.class);
        if (rawComp instanceof DirectionModeComponent dmc) {
            dirComp = dmc;
        }
        if (dirComp == null) {
            dirComp = (DirectionModeComponent) FunctionExecutor.INSTANCE.getComponent(DirectionModeComponent.class);
        }

        com.qiqi.li.living.core.model.Pos2D inputDir = com.qiqi.li.living.core.model.Pos2D.LEFT;
        com.qiqi.li.living.core.model.Pos2D fuelDir = com.qiqi.li.living.core.model.Pos2D.DOWN;
        com.qiqi.li.living.core.model.Pos2D outputDir = com.qiqi.li.living.core.model.Pos2D.RIGHT;

        if (dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS) {
            inputDir = dirComp.getDirection(null, "input");
            fuelDir = dirComp.getDirection(null, "fuel");
            outputDir = dirComp.getDirection(null, "output");
        }

        for (int hostSlot = 0; hostSlot < size; hostSlot++) {
            try {
                int width = getContainerWidth(container);
                int inputSlot = SlotResolver.resolve(hostSlot, inputDir, size, width);
                int fuelSlot = SlotResolver.resolve(hostSlot, fuelDir, size, width);
                int outputSlot = SlotResolver.resolve(hostSlot, outputDir, size, width);

                if (inputSlot != -1 && fuelSlot != -1 && outputSlot != -1) {
                    boolean allValid = validateSlots(container, inputSlot, fuelSlot, outputSlot);
                    if (allValid) {
                        slotMappings.put(hostSlot, new int[]{inputSlot, fuelSlot, outputSlot});
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Skipping invalid hostSlot {} for container of size {}", hostSlot, size);
            }
        }

        if (slotMappings.isEmpty()) {
            return null;
        }

        return new ContainerSlotMapping(slotMappings, container, System.currentTimeMillis());
    }

    /**
     * 验证三个槽位是否都可以安全访问
     */
    private boolean validateSlots(Container container, int... slots) {
        for (int slot : slots) {
            try {
                if (slot < 0 || slot >= container.getContainerSize()) {
                    return false;
                }
                container.getItem(slot);  // 测试访问是否成功
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清除最旧的缓存条目
     */
    private void evictOldestEntries(int count) {
        // 简单实现：直接清空一部分
        // 生产环境可以使用LRU策略
        if (!cache.isEmpty()) {
            var iterator = cache.int2ObjectEntrySet().iterator();
            int removed = 0;
            while (iterator.hasNext() && removed < count) {
                iterator.next();
                iterator.remove();
                removed++;
            }
        }
    }

    /**
     * 手动清除特定容器的缓存
     */
    public void invalidateContainer(Container container) {
        if (container == null) return;
        clearAll();
    }

    /**
     * 清除所有缓存（用于测试或重置）
     */
    public void clearAll() {
        synchronized (cache) {
            cache.clear();
            cacheHits = 0;
            cacheMisses = 0;
            evictions = 0;
        }
    }

    /**
     * 获取缓存统计信息（用于调试和监控）
     */
    public CacheStats getStats() {
        return new CacheStats(cache.size(), cacheHits, cacheMisses, evictions);
    }

    public record CacheStats(
        int cacheSize,
        long hits,
        long misses,
        long evictions
    ) {
        public double hitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }

    /**
     * 槽位映射结果对象
     */
    public static class ContainerSlotMapping {
        private final Int2ObjectMap<int[]> mappings;
        private final WeakReference<Container> containerRef;
        private final long creationTime;
        private volatile boolean valid = true;

        ContainerSlotMapping(Int2ObjectMap<int[]> mappings, Container container, long creationTime) {
            this.mappings = mappings;
            this.containerRef = new WeakReference<>(container);
            this.creationTime = creationTime;
        }

        /**
         * 获取指定hostSlot对应的[inputSlot, fuelSlot, outputSlot]
         * 如果该hostSlot位置无效则返回null
         */
        @Nullable
        public int[] resolveSlots(int hostSlot) {
            if (!valid) return null;
            return mappings.get(hostSlot);
        }

        /**
         * 获取所有有效的hostSlot位置
         */
        public int[] getValidHostSlots() {
            return mappings.keySet().toIntArray();
        }

        /**
         * 检查此映射是否仍然适用于给定的容器
         */
        public boolean isValidFor(Container container) {
            if (!valid) return false;
            
            Container cachedContainer = containerRef.get();
            if (cachedContainer == null) {
                valid = false;  // 容器已被GC
                return false;
            }

            return cachedContainer == container;
        }

        public int mappingCount() {
            return mappings.size();
        }

        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }
    }

    private int getContainerWidth(net.minecraft.world.Container container) {
        var adapter = com.qiqi.li.living.core.adapters.AdapterRegistry.getInstance().findAdapter(container);
        if (adapter != null) {
            try {
                var layout = adapter.getLayout(container);
                if (layout != null && layout.columns() > 0) {
                    return layout.columns();
                }
            } catch (Exception e) {
                // 回退到下一策略
            }
        }

        int size = container.getContainerSize();
        if (size > 0 && size % 9 != 0) {
            for (int w = 9; w >= 1; w--) {
                if (size % w == 0) return w;
            }
        }

        return SlotResolver.DEFAULT_WIDTH;
    }}