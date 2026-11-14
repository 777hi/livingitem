package com.qiqi.li.living.domain.portal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qiqi.li.living.container.ContainerDataKey;

/** 已点燃门面的不可变容器数据；只保存区域逻辑槽与点燃时列数，不给框材认主。 */
public record ContainerPortalData(List<PortalRegion> regions) {

    public static final ContainerPortalData EMPTY = new ContainerPortalData(List.of());

    /** 一扇门的固定区域；列数变化必须熄灭，区域不能自动扩张、拆分或合并。 */
    public record PortalRegion(int width, Set<Integer> slots) {
        private static final Codec<Set<Integer>> SLOTS_CODEC =
            Codec.intRange(0, Integer.MAX_VALUE).listOf().comapFlatMap(
                slots -> slots.isEmpty()
                    ? DataResult.error(() -> "Portal region must contain a slot")
                    : DataResult.success(Set.copyOf(slots)),
                slots -> new ArrayList<>(slots));

        public static final Codec<PortalRegion> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("width").forGetter(PortalRegion::width),
            SLOTS_CODEC.fieldOf("slots").forGetter(PortalRegion::slots)
        ).apply(i, PortalRegion::new));

        public PortalRegion {
            if (width <= 0 || slots == null || slots.isEmpty()
                    || slots.stream().anyMatch(slot -> slot == null || slot < 0)) {
                throw new IllegalArgumentException("Invalid portal region");
            }
            slots = Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(slots)));
        }

        public PortalRegion snapshot() {
            return new PortalRegion(width, slots);
        }
    }

    public static final Codec<ContainerPortalData> CODEC =
        PortalRegion.CODEC.listOf().comapFlatMap(regions -> {
            Set<Integer> seen = new LinkedHashSet<>();
            for (PortalRegion region : regions) {
                for (int slot : region.slots()) {
                    if (!seen.add(slot)) {
                        return DataResult.error(() -> "Portal regions must not overlap");
                    }
                }
            }
            return DataResult.success(new ContainerPortalData(regions));
        }, ContainerPortalData::regions);

    public static final Codec<java.util.Map<String, ContainerPortalData>> KEYED_CODEC =
        Codec.unboundedMap(Codec.STRING, CODEC);

    public static final ContainerDataKey<ContainerPortalData> KEY = ContainerDataKey.savedWith(
        "portal", () -> EMPTY,
        () -> PortalComponents.CONTAINER_PORTAL_DATA.value(),
        () -> CODEC, ContainerPortalData::isEmpty);

    public ContainerPortalData {
        List<PortalRegion> copied = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (PortalRegion region : regions) {
            for (int slot : region.slots()) {
                if (!seen.add(slot)) throw new IllegalArgumentException("Portal regions overlap");
            }
            copied.add(region.snapshot());
        }
        regions = Collections.unmodifiableList(copied);
    }

    public boolean isEmpty() {
        return regions.isEmpty();
    }

    public boolean contains(int slot) {
        return regions.stream().anyMatch(region -> region.slots().contains(slot));
    }

    public Set<Integer> portalSlots() {
        Set<Integer> slots = new LinkedHashSet<>();
        for (PortalRegion region : regions) slots.addAll(region.slots());
        return Collections.unmodifiableSet(slots);
    }

    /** 重复或重叠点火不产生第二份状态，也不改已有区域。 */
    public ContainerPortalData withRegion(int width, Set<Integer> slots) {
        if (slots.isEmpty() || slots.stream().anyMatch(this::contains)) return this;
        List<PortalRegion> updated = new ArrayList<>(regions);
        updated.add(new PortalRegion(width, slots));
        return new ContainerPortalData(updated);
    }

    public ContainerPortalData withoutRegion(PortalRegion region) {
        List<PortalRegion> updated = new ArrayList<>(regions);
        if (!updated.remove(region)) return this;
        return updated.isEmpty() ? EMPTY : new ContainerPortalData(updated);
    }

    /** 交接副本独立持有列表与区域集合；EMPTY 同样不包含可变共享数据。 */
    public ContainerPortalData snapshot() {
        return new ContainerPortalData(regions);
    }
}
