package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.Direction2D;
import net.minecraft.world.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 容器适配器注册中心
 * 
 * 管理所有可用的容器适配器，支持：
 * - 内置适配器（原版容器）
 * - 模组提供的适配器（通过ServiceLoader自动发现）
 * - 运行时动态注册（API方式）
 */
public final class AdapterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdapterRegistry.class);

    private static final AdapterRegistry INSTANCE = new AdapterRegistry();

    private final List<ContainerAdapter> adapters = new ArrayList<>();

    public static AdapterRegistry getInstance() {
        return INSTANCE;
    }

    private AdapterRegistry() {
        registerBuiltInAdapters();
        discoverExternalAdapters();
    }

    /**
     * 注册内置的原版容器适配器
     */
    private void registerBuiltInAdapters() {
        adapters.add(HopperAdapter.INSTANCE);
        
        // 可以添加更多原版容器适配器：
        // adapters.add(DropperAdapter.INSTANCE);
        // adapters.add(DispenserAdapter.INSTANCE);
        
        LOGGER.info("Registered {} built-in container adapters", adapters.size());
    }

    /**
     * 通过ServiceLoader机制发现外部模组提供的适配器
     * 
     * 模组只需：
     * 1. 创建实现 ContainerAdapter 的类
     * 2. 在 META-INF/services/com.qiqi.li.living.core.adapters.ContainerAdapter 文件中注册
     */
    private void discoverExternalAdapters() {
        try {
            ServiceLoader<ContainerAdapter> loader = 
                ServiceLoader.load(ContainerAdapter.class, AdapterRegistry.class.getClassLoader());
            
            int count = 0;
            for (ContainerAdapter adapter : loader) {
                if (!adapters.contains(adapter)) {
                    adapters.add(adapter);
                    count++;
                    LOGGER.info("Discovered external adapter: {}", adapter.getClass().getName());
                }
            }
            
            if (count > 0) {
                LOGGER.info("Loaded {} external container adapters", count);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to discover external adapters", e);
        }
    }

    /**
     * 手动注册适配器（供其他模组在运行时调用）
     */
    public void register(ContainerAdapter adapter) {
        if (adapter != null && !adapters.contains(adapter)) {
            adapters.add(adapter);
            LOGGER.info("Manually registered adapter: {}", adapter.getClass().getName());
        }
    }

    /**
     * 为指定容器查找合适的适配器
     * 
     * @return 第一个支持该容器的适配器，如果没有则返回null
     */
    @Nullable
    public ContainerAdapter findAdapter(Container container) {
        for (ContainerAdapter adapter : adapters) {
            try {
                if (adapter.supports(container)) {
                    return adapter;
                }
            } catch (Exception e) {
                LOGGER.debug("Adapter {} threw exception when checking support", 
                           adapter.getClass().getSimpleName(), e);
            }
        }
        return null;  // 使用默认的标准网格布局处理
    }

    /**
     * 使用适配器解析槽位（带降级策略）
     * 
     * 如果找到专用适配器就使用它，否则回退到标准SlotResolver
     */
    public int resolveWithAdapter(Container container, int hostSlot, Direction2D direction) {
        ContainerAdapter adapter = findAdapter(container);
        
        if (adapter != null) {
            try {
                int result = adapter.resolveSlot(container, hostSlot, direction);
                if (result != -1) {
                    return result;  // 适配器成功解析
                }
            } catch (Exception e) {
                LOGGER.warn("Adapter failed to resolve slot", e);
            }
        }
        
        // 降级到标准逻辑
        int size = safeGetSize(container);
        return com.qiqi.li.living.core.SlotResolver.resolve(hostSlot, direction, size);
    }

    private int safeGetSize(Container container) {
        try {
            return container.getContainerSize();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取所有已注册的适配器信息（用于调试）
     */
    public List<String> getAdapterInfo() {
        List<String> info = new ArrayList<>();
        for (ContainerAdapter adapter : adapters) {
            info.add(adapter.getClass().getSimpleName());
        }
        return info;
    }

    public int getAdapterCount() {
        return adapters.size();
    }
}