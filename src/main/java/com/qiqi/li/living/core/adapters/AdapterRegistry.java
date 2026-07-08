package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.world.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

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

    private void registerBuiltInAdapters() {
        adapters.add(HopperAdapter.INSTANCE);
        LOGGER.info("Registered {} built-in container adapters", adapters.size());
    }

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

    public void register(ContainerAdapter adapter) {
        if (adapter != null && !adapters.contains(adapter)) {
            adapters.add(adapter);
            LOGGER.info("Manually registered adapter: {}", adapter.getClass().getName());
        }
    }

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
        return com.qiqi.li.living.core.SlotResolver.resolve(hostSlot, direction, size);
    }

    private int safeGetSize(Container container) {
        try {
            return container.getContainerSize();
        } catch (Exception e) {
            return 0;
        }
    }

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