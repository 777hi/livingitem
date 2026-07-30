package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.SlotResolver;
import com.qiqi.li.living.components.ItemFilterComponent;
import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.data.LivingHopperData;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingHopperFunction;

public class ContainerSnapshot {

    public record ChestSnapshot(int usedSlots, int usedBytes, boolean isFull, boolean isByteFull) {
        public static final ChestSnapshot EMPTY = new ChestSnapshot(0, 0, false, false);
    }

    public static final ContainerSnapshot EMPTY = new ContainerSnapshot(0, new int[0], new int[0], new FilterData[0], new ChestSnapshot[0], ContainerFluidData.EMPTY);

    private final int containerSize;
    private final int[] sourceOf;
    private final int[] targetOf;
    private final FilterData[] filterOf;
    private final ChestSnapshot[] chestOf;
    private final ContainerFluidData fluidData;

    private ContainerSnapshot(int containerSize, int[] sourceOf, int[] targetOf,
                              FilterData[] filterOf, ChestSnapshot[] chestOf, ContainerFluidData fluidData) {
        this.containerSize = containerSize;
        this.sourceOf = sourceOf;
        this.targetOf = targetOf;
        this.filterOf = filterOf;
        this.chestOf = chestOf;
        this.fluidData = fluidData;
    }

    public static ContainerSnapshot capture(ContainerContext context, ContainerFluidData fluidData) {
        int containerSize = context.getSize();
        int containerWidth = context.getWidth();
        int[] sourceOf = new int[containerSize];
        int[] targetOf = new int[containerSize];
        Arrays.fill(sourceOf, -1);
        Arrays.fill(targetOf, -1);

        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!LivingHopperFunction.isLivingHopper(stack)) continue;

            LivingHopperData data = LivingItemManager.getHopperData(stack);
            if (data == null) continue;

            var dir = data.direction();
            sourceOf[slot] = SlotResolver.resolve(slot, dir.sourceOffset(), containerSize, containerWidth);
            targetOf[slot] = SlotResolver.resolve(slot, dir.targetOffset(), containerSize, containerWidth);
        }

        FilterData[] filterOf = buildAllFilters(context, containerSize, sourceOf, targetOf);
        ChestSnapshot[] chestOf = buildAllChestSnapshots(context, containerSize);

        return new ContainerSnapshot(containerSize, sourceOf, targetOf, filterOf, chestOf, fluidData);
    }

    private static ChestSnapshot[] buildAllChestSnapshots(ContainerContext ctx, int containerSize) {
        ChestSnapshot[] chestOf = new ChestSnapshot[containerSize];
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = ctx.getItem(slot);
            if (LivingChestFunction.isLivingChest(stack)) {
                int usedSlots = LivingChestFunction.countUsedSlots(stack);
                int usedBytes = LivingChestFunction.getCurrentByteUsage(stack);
                boolean isFull = usedSlots >= LivingChestFunction.CHEST_SLOTS;
                boolean isByteFull = usedBytes >= LivingChestFunction.MAX_STORAGE_BYTES;
                chestOf[slot] = new ChestSnapshot(usedSlots, usedBytes, isFull, isByteFull);
            }
        }
        return chestOf;
    }

    private static FilterData[] buildAllFilters(ContainerContext ctx, int containerSize,
                                                 int[] sourceOf, int[] targetOf) {
        FilterData[] filterOf = new FilterData[containerSize];
        Arrays.fill(filterOf, FilterData.EMPTY);

        for (int slot = 0; slot < containerSize; slot++) {
            if (sourceOf[slot] == -1 && targetOf[slot] == -1) continue;
            filterOf[slot] = buildFilterForSlot(slot, ctx, containerSize, sourceOf, targetOf, filterOf, new HashSet<>());
        }

        return filterOf;
    }

    private static FilterData buildFilterForSlot(int mySlot, ContainerContext ctx,
                                                  int containerSize, int[] sourceOf, int[] targetOf,
                                                  FilterData[] filterOf, Set<Integer> building) {
        List<String> blacklist = new ArrayList<>();
        List<String> whitelist = new ArrayList<>();
        List<Integer> blacklistSlots = new ArrayList<>();
        List<Integer> whitelistSlots = new ArrayList<>();
        List<String> blComposites = new ArrayList<>();
        List<String> wlComposites = new ArrayList<>();
        List<String> blTags = new ArrayList<>();
        List<String> wlTags = new ArrayList<>();
        List<Integer> blTagSlots = new ArrayList<>();
        List<Integer> wlTagSlots = new ArrayList<>();

        for (int i = 0; i < containerSize; i++) {
            if (i == mySlot) continue;

            int neighborSourceSlot = sourceOf[i];
            int neighborTargetSlot = targetOf[i];

            if (neighborSourceSlot == -1 && neighborTargetSlot == -1) continue;

            ItemStack neighborStack = ctx.getItem(i);
            int mode = ItemFilterComponent.normalizeMode(neighborStack.getCount());

            if (neighborTargetSlot == mySlot) {
                if (LivingHopperFunction.isLivingHopper(neighborStack)) {
                    FilterData neighborFilter = ensureFilterBuilt(i, ctx, containerSize, sourceOf, targetOf, filterOf, building);
                    mergeFilterData(neighborFilter, true,
                        blacklist, blComposites, blTags, blacklistSlots, blTagSlots);
                    mergeFilterData(neighborFilter, false,
                        whitelist, wlComposites, wlTags, whitelistSlots, wlTagSlots);
                }
                if (neighborSourceSlot >= 0 && neighborSourceSlot < containerSize) {
                    inheritFilter(ctx, containerSize, sourceOf, targetOf,
                        neighborSourceSlot, mode, true,
                        blacklist, blComposites, blTags, blacklistSlots, blTagSlots,
                        filterOf, building, new HashSet<>());
                }
            }

            if (neighborSourceSlot == mySlot) {
                if (LivingHopperFunction.isLivingHopper(neighborStack)) {
                    FilterData neighborFilter = ensureFilterBuilt(i, ctx, containerSize, sourceOf, targetOf, filterOf, building);
                    mergeFilterData(neighborFilter, true,
                        blacklist, blComposites, blTags, blacklistSlots, blTagSlots);
                    mergeFilterData(neighborFilter, false,
                        whitelist, wlComposites, wlTags, whitelistSlots, wlTagSlots);
                }
                if (neighborTargetSlot >= 0 && neighborTargetSlot < containerSize) {
                    inheritFilter(ctx, containerSize, sourceOf, targetOf,
                        neighborTargetSlot, mode, false,
                        whitelist, wlComposites, wlTags, whitelistSlots, wlTagSlots,
                        filterOf, building, new HashSet<>());
                }
            }
        }

        if (blacklist.isEmpty() && whitelist.isEmpty() && blComposites.isEmpty()
            && wlComposites.isEmpty() && blTags.isEmpty() && wlTags.isEmpty()) {
            return FilterData.EMPTY;
        }

        return new FilterData(blacklist, whitelist, blacklistSlots, whitelistSlots,
            blComposites, wlComposites, blTags, wlTags, blTagSlots, wlTagSlots);
    }

    private static void inheritFilter(ContainerContext ctx, int containerSize,
                                       int[] sourceOf, int[] targetOf,
                                       int slot, int mode, boolean isBlacklist,
                                       List<String> idList, List<String> compositeList,
                                       List<String> tagList, List<Integer> idSlots,
                                       List<Integer> tagSlots,
                                       FilterData[] filterOf, Set<Integer> building,
                                       Set<Integer> visited) {
        if (slot < 0 || slot >= containerSize || visited.contains(slot)) return;
        visited.add(slot);

        ItemStack item = ctx.getItem(slot);
        if (item.isEmpty()) return;

        if (LivingHopperFunction.isLivingHopper(item)) {
            FilterData neighborFilter = ensureFilterBuilt(slot, ctx, containerSize, sourceOf, targetOf, filterOf, building);
            mergeFilterData(neighborFilter, isBlacklist,
                idList, compositeList, tagList, idSlots, tagSlots);
            int nextSlot = isBlacklist ? sourceOf[slot] : targetOf[slot];
            int hopperMode = ItemFilterComponent.normalizeMode(item.getCount());
            if (nextSlot >= 0 && nextSlot < containerSize) {
                inheritFilter(ctx, containerSize, sourceOf, targetOf,
                    nextSlot, hopperMode, isBlacklist,
                    idList, compositeList, tagList, idSlots, tagSlots,
                    filterOf, building, visited);
            }
            return;
        }

        if (LivingItemManager.isLivingItem(item)) return;

        addToFilter(mode, item, idList, compositeList, tagList, idSlots, tagSlots, slot);
    }

    private static FilterData ensureFilterBuilt(int slot, ContainerContext ctx, int containerSize,
                                                 int[] sourceOf, int[] targetOf,
                                                 FilterData[] filterOf, Set<Integer> building) {
        if (!filterOf[slot].equals(FilterData.EMPTY)) {
            return filterOf[slot];
        }
        if (building.contains(slot)) {
            return FilterData.EMPTY;
        }
        building.add(slot);
        filterOf[slot] = buildFilterForSlot(slot, ctx, containerSize, sourceOf, targetOf, filterOf, building);
        building.remove(slot);
        return filterOf[slot];
    }

    private static void mergeFilterData(FilterData source, boolean isBlacklist,
                                         List<String> idList, List<String> compositeList,
                                         List<String> tagList, List<Integer> idSlots,
                                         List<Integer> tagSlots) {
        if (isBlacklist) {
            for (String id : source.blacklist()) {
                if (!idList.contains(id)) idList.add(id);
            }
            for (int s : source.blacklistSlots()) {
                if (!idSlots.contains(s)) idSlots.add(s);
            }
            for (String c : source.blComposites()) {
                if (!compositeList.contains(c)) compositeList.add(c);
            }
            for (String t : source.blTags()) {
                if (!tagList.contains(t)) tagList.add(t);
            }
            for (int s : source.blTagSlots()) {
                if (!tagSlots.contains(s)) tagSlots.add(s);
            }
        } else {
            for (String id : source.whitelist()) {
                if (!idList.contains(id)) idList.add(id);
            }
            for (int s : source.whitelistSlots()) {
                if (!idSlots.contains(s)) idSlots.add(s);
            }
            for (String c : source.wlComposites()) {
                if (!compositeList.contains(c)) compositeList.add(c);
            }
            for (String t : source.wlTags()) {
                if (!tagList.contains(t)) tagList.add(t);
            }
            for (int s : source.wlTagSlots()) {
                if (!tagSlots.contains(s)) tagSlots.add(s);
            }
        }
    }

    private static void addToFilter(int mode, ItemStack stack,
                                     List<String> idList, List<String> compositeList, List<String> tagList,
                                     List<Integer> idSlots, List<Integer> tagSlots, int refSlot) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        switch (mode) {
            case ItemFilterComponent.MODE_COMPONENT -> {
                String composite = ItemFilterComponent.getCompositeKey(stack);
                if (!compositeList.contains(composite)) {
                    compositeList.add(composite);
                }
            }
            case ItemFilterComponent.MODE_TAG -> {
                Set<String> tags = ItemFilterComponent.collectItemTags(stack);
                for (String tag : tags) {
                    if (!tagList.contains(tag)) {
                        tagList.add(tag);
                        tagSlots.add(refSlot);
                    }
                }
            }
            default -> {
                if (!idList.contains(itemId)) {
                    idList.add(itemId);
                    idSlots.add(refSlot);
                }
            }
        }
    }

    public int getContainerSize() {
        return containerSize;
    }

    public int[] getSourceOf() {
        return sourceOf;
    }

    public int[] getTargetOf() {
        return targetOf;
    }

    public FilterData[] getFilterOf() {
        return filterOf;
    }

    public int getSourceOf(int slot) {
        return slot >= 0 && slot < containerSize ? sourceOf[slot] : -1;
    }

    public int getTargetOf(int slot) {
        return slot >= 0 && slot < containerSize ? targetOf[slot] : -1;
    }

    public FilterData getFilterOf(int slot) {
        return slot >= 0 && slot < containerSize ? filterOf[slot] : FilterData.EMPTY;
    }

    public ContainerFluidData getFluidData() {
        return fluidData;
    }

    public ChestSnapshot getChestSnapshot(int slot) {
        return slot >= 0 && slot < containerSize && chestOf[slot] != null
            ? chestOf[slot] : ChestSnapshot.EMPTY;
    }

    public ChestSnapshot[] getChestSnapshots() {
        return chestOf;
    }
}