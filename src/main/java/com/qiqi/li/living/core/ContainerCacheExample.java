package com.qiqi.li.living.core;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/**
 * 使用ContainerCacheManager的示例代码
 * 
 * 演示如何在活物品系统中集成预计算缓存
 */
public class ContainerCacheExample {

    /**
     * 改进后的FunctionExecutor - 使用缓存版本
     */
    public static class CachedFunctionExecutor {

        public void tick(Container container, int slot, ItemStack stack,
                         LivingFunctionConfig config) {
            
            // ✅ 步骤1：获取或创建槽位映射（自动处理缓存）
            ContainerCacheManager.ContainerSlotMapping mapping = 
                ContainerCacheManager.getInstance().getOrCreateMapping(container, config);
            
            if (mapping == null) {
                return;  // 容器无效或无法映射
            }
            
            // ✅ 步骤2：从缓存中快速查询（O(1)时间复杂度）
            int[] slots = mapping.resolveSlots(slot);
            if (slots == null) {
                return;  // 当前hostSlot位置不合适（可能在边缘）
            }
            
            int inputSlot = slots[0];
            int fuelSlot = slots[1];  
            int outputSlot = slots[2];
            
            // ✅ 步骤3：执行业务逻辑（这些槽位已经过验证）
            processFurnaceLogic(container, inputSlot, fuelSlot, outputSlot);
        }

        private void processFurnaceLogic(Container container, int inputSlot, 
                                         int fuelSlot, int outputSlot) {
            // 正常的熔炉逻辑...
            ItemStack input = safeGetItem(container, inputSlot);
            ItemStack fuel = safeGetItem(container, fuelSlot);
            // ...
        }

        private ItemStack safeGetItem(Container container, int slot) {
            try {
                if (slot >= 0 && slot < container.getContainerSize()) {
                    return container.getItem(slot);
                }
            } catch (Exception e) {
                // 记录日志但继续运行
            }
            return ItemStack.EMPTY;
        }
    }

    /**
     * 容器销毁时的清理逻辑
     */
    public static class ContainerLifecycleManager {

        public static void onContainerDestroying(Container container) {
            // 当容器即将被销毁时，清除其缓存
            ContainerCacheManager.getInstance().invalidateContainer(container);
        }

        public static void onWorldUnload() {
            // 世界卸载时清除所有缓存
            ContainerCacheManager.getInstance().clearAll();
        }
    }

    /**
     * 调试和监控工具
     */
    public static class CacheMonitor {

        public static void printStats() {
            var stats = ContainerCacheManager.getInstance().getStats();
            System.out.println("=== 容器缓存统计 ===");
            System.out.println("缓存条目数: " + stats.cacheSize());
            System.out.println("命中次数: " + stats.hits());
            System.out.println("未命中次数: " + stats.misses());
            System.out.println("命中率: " + String.format("%.2f%%", stats.hitRate() * 100));
            System.out.println("淘汰次数: " + stats.evictions());
        }

        public static void logPerformanceWarning() {
            var stats = ContainerCacheManager.getInstance().getStats();
            if (stats.hitRate() < 0.5 && stats.misses() > 1000) {
                System.out.println("警告: 缓存命中率过低 (" + 
                    String.format("%.2f%%", stats.hitRate() * 100) + ")，可能存在大量不同类型的容器");
            }
        }
    }
}