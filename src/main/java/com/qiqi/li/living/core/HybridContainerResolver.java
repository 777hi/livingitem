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

/**
 * 混合容器解析器 —— 多策略槽位解析器，支持多种容器类型。
 *
 * 解析策略优先级（从高到低）：
 * 1. 缓存解析：从 ContainerCacheManager 获取已缓存的映射
 * 2. 配置解析：从 ContainerCompatibilityConfig 查找容器规则
 * 3. 适配器解析：从 AdapterRegistry 查找容器适配器
 * 4. 标准解析：使用 SlotResolver 的 9 列网格布局解析
 *
 * 每种策略独立尝试，第一种成功即返回。
 * 如果启用运行时验证（enableRuntimeValidation），
 * 标准解析的结果还会通过 RuntimeContainerValidator 进行额外校验。
 *
 * 适用场景：
 * - 标准容器（箱子、背包）：使用标准 9 列网格解析
 * - 非标准容器（漏斗、特殊模组容器）：使用适配器或配置规则
 * - 性能优化：缓存解析避免重复计算
 */
public final class HybridContainerResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridContainerResolver.class);

    /** 单例实例 */
    public static final HybridContainerResolver INSTANCE = new HybridContainerResolver();

    private final ContainerCacheManager cacheManager = ContainerCacheManager.getInstance();
    private final AdapterRegistry adapterRegistry = AdapterRegistry.getInstance();
    private final RuntimeContainerValidator runtimeValidator = RuntimeContainerValidator.INSTANCE;

    /** 是否启用运行时验证（默认关闭，影响性能） */
    private boolean enableRuntimeValidation = false;

    private HybridContainerResolver() {}

    /**
     * 解析活物品的输入/燃料/输出槽位。
     *
     * @param container 目标容器
     * @param hostSlot 活物品所在槽位
     * @param config 活物品功能配置
     * @return 解析结果（包含槽位数组和来源信息）
     */
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

    /** 尝试从缓存获取槽位映射 */
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

    /** 尝试通过配置规则解析槽位 */
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

    /** 尝试通过适配器解析槽位 */
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

    /** 尝试通过标准 9 列网格解析槽位 */
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

            int width = getContainerWidth(container);
            int inputSlot = SlotResolver.resolve(hostSlot, inputDir, size, width);
            int fuelSlot = SlotResolver.resolve(hostSlot, fuelDir, size, width);
            int outputSlot = SlotResolver.resolve(hostSlot, outputDir, size, width);

            if (inputSlot != -1 && fuelSlot != -1 && outputSlot != -1) {
                return ResolveResult.valid(new int[]{inputSlot, fuelSlot, outputSlot});
            }
        } catch (Exception e) {
            LOGGER.debug("Standard resolution failed", e);
        }

        return ResolveResult.invalid("Standard resolution failed");
    }

    /** 运行时验证：检查解析出的槽位是否可安全访问 */
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

    /** 通过类名识别容器类型 */
    @Nullable
    private ResourceLocation identifyContainer(Container container) {
        if (container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            try {
                ResourceLocation beId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                if (beId != null) {
                    var rule = ContainerCompatibilityConfig.findRule(beId);
                    if (rule.isPresent()) return beId;

                    String ns = beId.getNamespace();
                    String path = beId.getPath();
                    if ("minecraft".equals(ns)) {
                        if (path.contains("chest")) return ResourceLocation.fromNamespaceAndPath("minecraft", "chest");
                        if (path.contains("hopper")) return ResourceLocation.fromNamespaceAndPath("minecraft", "hopper");
                    } else {
                        return beId;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to identify container by BlockEntityType: {}", container.getClass().getSimpleName(), e);
            }
        }

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

    /** 根据配置规则的方向偏移计算目标槽位 */
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

    /** 安全获取容器大小（带异常保护） */
    private int safeGetSize(Container container) {
        try {
            return container.getContainerSize();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 设置是否启用运行时验证 */
    public void setRuntimeValidation(boolean enabled) {
        this.enableRuntimeValidation = enabled;
        LOGGER.info("Runtime validation {}", enabled ? "ENABLED" : "DISABLED");
    }

    /**
     * 解析结果 —— 封装槽位解析的输出。
     *
     * 包含：
     * - valid：解析是否成功
     * - slots：解析出的槽位数组 [inputSlot, fuelSlot, outputSlot]
     * - source：解析来源（CACHE/CONFIG/ADAPTER/STANDARD/INVALID）
     * - reason：失败原因
     */
    public static class ResolveResult {
        /** 解析来源 */
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

    private int getContainerWidth(Container container) {
        var adapter = AdapterRegistry.getInstance().findAdapter(container);
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

        ResourceLocation containerId = identifyContainer(container);
        if (containerId != null) {
            var rule = ContainerCompatibilityConfig.findRule(containerId);
            if (rule.isPresent() && rule.get().columns() > 0) {
                return rule.get().columns();
            }
        }

        var sizeRule = ContainerCompatibilityConfig.findRuleBySize(container.getContainerSize());
        if (sizeRule.isPresent() && sizeRule.get().columns() > 0) {
            return sizeRule.get().columns();
        }

        int size = container.getContainerSize();
        if (size > 0 && size % 9 != 0) {
            for (int w = 9; w >= 1; w--) {
                if (size % w == 0) return w;
            }
        }

        return SlotResolver.DEFAULT_WIDTH;
    }}