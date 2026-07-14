package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.world.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 适配器注册表 —— 管理所有容器适配器的注册和查找。
 *
 * 职责：
 * 1. 注册内置适配器（如 HopperAdapter）
 * 2. 通过 ServiceLoader 发现外部适配器（模组扩展点）
 * 3. 根据容器类型查找合适的适配器
 * 4. 提供适配器回退机制（无适配器时使用 SlotResolver）
 *
 * 适配器查找策略：
 *   遍历所有已注册适配器，调用 supports() 方法，
 *   返回第一个匹配的适配器。
 *   如果没有适配器匹配，resolveWithAdapter() 回退到 SlotResolver。
 */
public final class AdapterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdapterRegistry.class);

    /** 单例实例 */
    private static final AdapterRegistry INSTANCE = new AdapterRegistry();

    /** 已注册的适配器列表（按注册顺序查找） */
    private final List<ContainerAdapter> adapters = new ArrayList<>();

    public static AdapterRegistry getInstance() {
        return INSTANCE;
    }

    private AdapterRegistry() {
        registerBuiltInAdapters();
        discoverExternalAdapters();
    }

    /** 注册内置适配器 */
    private void registerBuiltInAdapters() {
        adapters.add(HopperAdapter.INSTANCE);
        LOGGER.info("Registered {} built-in container adapters", adapters.size());
    }

    /** 通过 Java ServiceLoader 发现外部适配器（模组扩展点） */
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
     * 手动注册适配器。
     *
     * @param adapter 要注册的适配器
     */
    public void register(ContainerAdapter adapter) {
        if (adapter != null && !adapters.contains(adapter)) {
            adapters.add(adapter);
            LOGGER.info("Manually registered adapter: {}", adapter.getClass().getName());
        }
    }

    /**
     * 查找支持指定容器的适配器。
     *
     * @param container 目标容器
     * @return 匹配的适配器，如果没有返回 null
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
        return null;
    }

    /**
     * 使用适配器解析槽位（带回退机制）。
     *
     * 优先使用适配器解析，如果没有适配器或适配器返回 -1，
     * 回退到 SlotResolver 的标准 9 列网格解析。
     *
     * @param container 目标容器
     * @param hostSlot 宿主槽位
     * @param direction 方向偏移
     * @return 解析出的槽位索引，无效返回 -1
     */
    public int resolveWithAdapter(Container container, int hostSlot, Pos2D direction) {
        ContainerAdapter adapter = findAdapter(container);

        if (adapter != null) {
            try {
                int result = adapter.resolveSlot(container, hostSlot, direction);
                if (result != -1) {
                    return result;
                }
            } catch (Exception e) {
                LOGGER.warn("Adapter failed to resolve slot", e);
            }
        }

        int size = safeGetSize(container);
        int width = getContainerWidth(adapter, container);
        return com.qiqi.li.living.core.SlotResolver.resolve(hostSlot, direction, size, width);
    }

    /** 安全获取容器大小 */
    private int safeGetSize(Container container) {
        try {
            return container.getContainerSize();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 获取已注册适配器的类名列表（调试用） */
    public List<String> getAdapterInfo() {
        List<String> info = new ArrayList<>();
        for (ContainerAdapter adapter : adapters) {
            info.add(adapter.getClass().getSimpleName());
        }
        return info;
    }

    /** 获取已注册适配器数量 */
    public int getAdapterCount() {
        return adapters.size();
    }

    private int getContainerWidth(ContainerAdapter adapter, Container container) {
        if (adapter != null) {
            try {
                var layout = adapter.getLayout(container);
                if (layout != null && layout.columns() > 0) {
                    return layout.columns();
                }
            } catch (Exception e) {
                // 回退到默认值
            }
        }
        return com.qiqi.li.living.core.SlotResolver.DEFAULT_WIDTH;
    }}