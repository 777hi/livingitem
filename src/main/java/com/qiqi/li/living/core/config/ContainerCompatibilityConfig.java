package com.qiqi.li.living.core.config;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class ContainerCompatibilityConfig {

    private static final Map<ResourceLocation, ContainerRule> RULES = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCompatibilityConfig.class);

    static {
        initializeDefaultRules();
    }

    private ContainerCompatibilityConfig() {}

    private static void initializeDefaultRules() {
        try {
            register(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"), ContainerRule.builder()
                .containerSize(27)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 26))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -9)
                .directionMapping(Pos2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("minecraft", "double_chest"), ContainerRule.builder()
                .containerSize(54)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 53))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -9)
                .directionMapping(Pos2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.WRAP)
                .crossBlockEntitySupport(true)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("minecraft", "hopper"), ContainerRule.builder()
                .containerSize(5)
                .layoutType(ContainerLayoutType.LINEAR)
                .validHostSlots(Arrays.asList(1, 2, 3))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -1)
                .directionMapping(Pos2D.DOWN, -1)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            register(ResourceLocation.fromNamespaceAndPath("ironchests", "iron_chest"), ContainerRule.builder()
                .containerSize(45)
                .layoutType(ContainerLayoutType.RECTANGULAR_STANDARD)
                .validHostSlots(range(0, 44))
                .directionMapping(Pos2D.LEFT, -1)
                .directionMapping(Pos2D.RIGHT, 1)
                .directionMapping(Pos2D.UP, -9)
                .directionMapping(Pos2D.DOWN, 9)
                .edgeBehavior(EdgeBehavior.INVALIDATE)
                .build()
            );

            LOGGER.info("Initialized {} default container compatibility rules", RULES.size());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize default container rules", e);
        }
    }

    public static void register(ResourceLocation containerId, ContainerRule rule) {
        RULES.put(containerId, rule);
    }

    public static Optional<ContainerRule> findRule(ResourceLocation containerId) {
        return Optional.ofNullable(RULES.get(containerId));
    }

    public static Set<Map.Entry<ResourceLocation, ContainerRule>> getAllRules() {
        return Collections.unmodifiableSet(RULES.entrySet());
    }

    public static void loadFromJson(String jsonPath) {
    }

    private static List<Integer> range(int start, int end) {
        List<Integer> list = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            list.add(i);
        }
        return list;
    }

    public enum ContainerLayoutType {
        RECTANGULAR_STANDARD,
        RECTANGULAR_CUSTOM,
        LINEAR,
        IRREGULAR
    }

    public enum EdgeBehavior {
        INVALIDATE,
        WRAP,
        CLAMP,
        SKIP
    }

    public record ContainerRule(
        int containerSize,
        ContainerLayoutType layoutType,
        List<Integer> validHostSlots,
        Map<Pos2D, Integer> directionMappings,
        EdgeBehavior edgeBehavior,
        boolean crossBlockEntitySupport,
        String description
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public boolean isValidHostSlot(int slot) {
            return validHostSlots.contains(slot);
        }

        public int getDirectionOffset(Pos2D direction) {
            return directionMappings.getOrDefault(direction, 0);
        }
    }

    public static class Builder {
        private int containerSize = 0;
        private ContainerLayoutType layoutType = ContainerLayoutType.RECTANGULAR_STANDARD;
        private List<Integer> validHostSlots = new ArrayList<>();
        private Map<Pos2D, Integer> directionMappings = new HashMap<>();
        private EdgeBehavior edgeBehavior = EdgeBehavior.INVALIDATE;
        private boolean crossBlockEntitySupport = false;
        private String description = "";

        public Builder containerSize(int size) {
            this.containerSize = size;
            return this;
        }

        public Builder layoutType(ContainerLayoutType type) {
            this.layoutType = type;
            return this;
        }

        public Builder validHostSlots(List<Integer> slots) {
            this.validHostSlots = slots;
            return this;
        }

        public Builder directionMapping(Pos2D direction, int offset) {
            this.directionMappings.put(direction, offset);
            return this;
        }

        public Builder edgeBehavior(EdgeBehavior behavior) {
            this.edgeBehavior = behavior;
            return this;
        }

        public Builder crossBlockEntitySupport(boolean support) {
            this.crossBlockEntitySupport = support;
            return this;
        }

        public Builder description(String desc) {
            this.description = desc;
            return this;
        }

        public ContainerRule build() {
            if (containerSize <= 0) {
                throw new IllegalStateException("Container size must be positive");
            }
            if (validHostSlots.isEmpty()) {
                validHostSlots = range(0, containerSize - 1);
            }
            return new ContainerRule(
                containerSize, layoutType, validHostSlots,
                directionMappings, edgeBehavior, crossBlockEntitySupport, description
            );
        }
    }
}