package com.qiqi.li.living.core;

import com.qiqi.li.living.core.adapters.AdapterRegistry;
import com.qiqi.li.living.core.adapters.ContainerAdapter;
import com.qiqi.li.living.core.config.ContainerCompatibilityConfig;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.components.ILivingComponent;
import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public final class HybridContainerResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridContainerResolver.class);

    public static final HybridContainerResolver INSTANCE = new HybridContainerResolver();

    private final ContainerCacheManager cacheManager = ContainerCacheManager.getInstance();
    private final AdapterRegistry adapterRegistry = AdapterRegistry.getInstance();
    private final RuntimeContainerValidator runtimeValidator = RuntimeContainerValidator.INSTANCE;

    private boolean enableRuntimeValidation = false;

    private HybridContainerResolver() {}

    public ResolveResult resolve(
        Container container,
        int hostSlot,
        LivingFunctionConfig config
    ) {
        if (container == null || config == null) {
            return ResolveResult.invalid("Null container or config");
        }

        DirectionModeComponent dirComp = null;
        ILivingComponent rawComp = config.getConfiguredInstance(DirectionModeComponent.class);
        if (rawComp instanceof DirectionModeComponent dmc) {
            dirComp = dmc;
        }
        if (dirComp == null) {
            dirComp = (DirectionModeComponent) FunctionExecutor.INSTANCE.getComponent(DirectionModeComponent.class);
        }

        ComponentState dirState = null;
        var functionData = new net.minecraft.nbt.CompoundTag();

        ContainerCacheManager.ContainerSlotMapping cachedMapping =
            tryCacheResolve(container, config, hostSlot);

        if (cachedMapping != null) {
            int[] slots = cachedMapping.resolveSlots(hostSlot);
            if (slots != null) {
                return ResolveResult.cached(slots);
            }
        }

        ResolveResult configResult = tryConfigResolve(container, hostSlot, config, dirComp);
        if (configResult.isValid()) {
            return configResult.withSource(ResolveResult.Source.CONFIG);
        }

        ResolveResult adapterResult = tryAdapterResolve(container, hostSlot, config, dirComp);
        if (adapterResult.isValid()) {
            return adapterResult.withSource(ResolveResult.Source.ADAPTER);
        }

        ResolveResult standardResult = tryStandardResolve(container, hostSlot, config, dirComp);

        if (enableRuntimeValidation && standardResult.isValid()) {
            if (!runtimeValidate(container, standardResult.getSlots())) {
                LOGGER.warn("Standard resolution failed runtime validation for host slot {}", hostSlot);
                standardResult = ResolveResult.invalid("Failed runtime validation");
            }
        }

        if (standardResult.isValid()) {
            return standardResult.withSource(ResolveResult.Source.STANDARD);
        }

        LOGGER.debug("All resolution methods failed for host slot {} in container", hostSlot);
        return ResolveResult.invalid("All resolution methods failed");
    }

    @Nullable
    private ContainerCacheManager.ContainerSlotMapping tryCacheResolve(
        Container container, LivingFunctionConfig config, int hostSlot
    ) {
        try {
            return cacheManager.getOrCreateMapping(container, config);
        } catch (Exception e) {
            LOGGER.debug("Cache resolution failed", e);
            return null;
        }
    }

    private ResolveResult tryConfigResolve(
        Container container, int hostSlot, LivingFunctionConfig config,
        DirectionModeComponent dirComp
    ) {
        try {
            ResourceLocation containerId = identifyContainer(container);

            if (containerId != null) {
                var ruleOpt = ContainerCompatibilityConfig.findRule(containerId);

                if (ruleOpt.isPresent()) {
                    var rule = ruleOpt.get();

                    if (!rule.isValidHostSlot(hostSlot)) {
                        return ResolveResult.invalid("Host slot not in valid range per config");
                    }

                    int size = safeGetSize(container);

                    Pos2D inputDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                        ? dirComp.getDirection(null, "input") : Pos2D.LEFT;
                    Pos2D fuelDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                        ? dirComp.getDirection(null, "fuel") : Pos2D.DOWN;
                    Pos2D outputDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                        ? dirComp.getDirection(null, "output") : Pos2D.RIGHT;

                    int inputSlot = applyDirectionRule(rule, hostSlot, inputDir, size);
                    int fuelSlot = applyDirectionRule(rule, hostSlot, fuelDir, size);
                    int outputSlot = applyDirectionRule(rule, hostSlot, outputDir, size);

                    if (inputSlot != -1 && fuelSlot != -1 && outputSlot != -1) {
                        return ResolveResult.valid(new int[]{inputSlot, fuelSlot, outputSlot});
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Config resolution failed", e);
        }

        return ResolveResult.invalid("No applicable config rule");
    }

    private ResolveResult tryAdapterResolve(
        Container container, int hostSlot, LivingFunctionConfig config,
        DirectionModeComponent dirComp
    ) {
        try {
            ContainerAdapter adapter = adapterRegistry.findAdapter(container);

            if (adapter != null) {
                if (!adapter.isValidHostPosition(container, hostSlot)) {
                    return ResolveResult.invalid("Adapter rejected host position");
                }

                Pos2D inputDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                    ? dirComp.getDirection(null, "input") : Pos2D.LEFT;
                Pos2D fuelDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                    ? dirComp.getDirection(null, "fuel") : Pos2D.DOWN;
                Pos2D outputDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                    ? dirComp.getDirection(null, "output") : Pos2D.RIGHT;

                int inputSlot = adapter.resolveSlot(container, hostSlot, inputDir);
                int fuelSlot = adapter.resolveSlot(container, hostSlot, fuelDir);
                int outputSlot = adapter.resolveSlot(container, hostSlot, outputDir);

                if (inputSlot != -1 && fuelSlot != -1 && outputSlot != -1) {
                    return ResolveResult.valid(new int[]{inputSlot, fuelSlot, outputSlot});
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Adapter resolution failed", e);
        }

        return ResolveResult.invalid("No suitable adapter found");
    }

    private ResolveResult tryStandardResolve(
        Container container, int hostSlot, LivingFunctionConfig config,
        DirectionModeComponent dirComp
    ) {
        try {
            int size = safeGetSize(container);

            if (size <= 0 || hostSlot < 0 || hostSlot >= size) {
                return ResolveResult.invalid("Invalid container or host slot");
            }

            Pos2D inputDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                ? dirComp.getDirection(null, "input") : Pos2D.LEFT;
            Pos2D fuelDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                ? dirComp.getDirection(null, "fuel") : Pos2D.DOWN;
            Pos2D outputDir = dirComp != null && dirComp.getMode() == DirectionModeComponent.DirectionMode.SLOTS
                ? dirComp.getDirection(null, "output") : Pos2D.RIGHT;

            int inputSlot = SlotResolver.resolve(hostSlot, inputDir, size);
            int fuelSlot = SlotResolver.resolve(hostSlot, fuelDir, size);
            int outputSlot = SlotResolver.resolve(hostSlot, outputDir, size);

            if (inputSlot != -1 && fuelSlot != -1 && outputSlot != -1) {
                return ResolveResult.valid(new int[]{inputSlot, fuelSlot, outputSlot});
            }
        } catch (Exception e) {
            LOGGER.debug("Standard resolution failed", e);
        }

        return ResolveResult.invalid("Standard resolution failed");
    }

    private boolean runtimeValidate(Container container, int[] slots) {
        try {
            var result = runtimeValidator.resolveAndValidate(
                container, slots[0],
                Pos2D.LEFT, Pos2D.DOWN, Pos2D.RIGHT
            );
            return result.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private ResourceLocation identifyContainer(Container container) {
        String className = container.getClass().getSimpleName();

        try {
            if (className.contains("Chest")) {
                return ResourceLocation.fromNamespaceAndPath("minecraft", "chest");
            } else if (className.contains("Hopper")) {
                return ResourceLocation.fromNamespaceAndPath("minecraft", "hopper");
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to create ResourceLocation for container type: {}", className, e);
        }

        return null;
    }

    private int applyDirectionRule(ContainerCompatibilityConfig.ContainerRule rule,
                                   int baseSlot, Pos2D direction, int containerSize) {
        int offset = rule.getDirectionOffset(direction);
        if (offset == 0 && direction != Pos2D.NONE) {
            return -1;
        }

        int result = baseSlot + offset;

        switch (rule.edgeBehavior()) {
            case INVALIDATE:
                if (result < 0 || result >= containerSize) return -1;
                break;
            case WRAP:
                result = ((result % containerSize) + containerSize) % containerSize;
                break;
            case CLAMP:
                result = Math.max(0, Math.min(result, containerSize - 1));
                break;
            case SKIP:
                return -1;
        }

        return result;
    }

    private int safeGetSize(Container container) {
        try {
            return container.getContainerSize();
        } catch (Exception e) {
            return 0;
        }
    }

    public void setRuntimeValidation(boolean enabled) {
        this.enableRuntimeValidation = enabled;
        LOGGER.info("Runtime validation {}", enabled ? "ENABLED" : "DISABLED");
    }

    public static class ResolveResult {
        enum Source { CACHE, CONFIG, ADAPTER, STANDARD, INVALID }

        private final boolean valid;
        private final int[] slots;
        private final String reason;
        private Source source = Source.INVALID;

        private ResolveResult(boolean valid, int[] slots, String reason) {
            this.valid = valid;
            this.slots = slots;
            this.reason = reason;
        }

        public static ResolveResult valid(int[] slots) {
            return new ResolveResult(true, slots, null);
        }

        public static ResolveResult cached(int[] slots) {
            var result = new ResolveResult(true, slots, null);
            result.source = Source.CACHE;
            return result;
        }

        public static ResolveResult invalid(String reason) {
            return new ResolveResult(false, null, reason);
        }

        public ResolveResult withSource(Source source) {
            this.source = source;
            return this;
        }

        public boolean isValid() { return valid; }
        public int[] getSlots() { return slots; }
        public String getReason() { return reason; }
        public Source getSource() { return source; }

        @Override
        public String toString() {
            return String.format("ResolveResult{valid=%s, source=%s, reason=%s}",
                               valid, source, reason);
        }
    }
}