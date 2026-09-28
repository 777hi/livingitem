package com.qiqi.li.living.domain.hopper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.ItemFilterComponent;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.transfer.FilterData;

/**
 * 活漏斗过滤构建器 —— 从容器上下文中构建所有活漏斗的过滤数据。
 *
 * <p>从 {@code ContainerSnapshot.buildAllFilters} 提取而来，
 * 使过滤构建逻辑归属于活漏斗领域，而非容器基础设施。</p>
 *
 * <h3>算法</h3>
 * <p>对每个活漏斗槽位，遍历容器中所有其他槽位：</p>
 * <ul>
 *   <li>如果其他活漏斗的目标指向当前槽位 → 继承其黑/白名单</li>
 *   <li>如果其他活漏斗的源指向当前槽位 → 继承其白/黑名单（方向反转）</li>
 *   <li>递归处理活漏斗链（通过 ensureFilterBuilt 防止循环）</li>
 * </ul>
 */
public final class HopperFilterBuilder {

    private HopperFilterBuilder() {}

    public static FilterData[] buildAll(ContainerContext ctx, int containerSize,
                                         int[] sourceOf, int[] targetOf) {
        FilterData[] filterOf = new FilterData[containerSize];
        java.util.Arrays.fill(filterOf, FilterData.EMPTY);

        for (int slot = 0; slot < containerSize; slot++) {
            if (sourceOf[slot] == -1 && targetOf[slot] == -1) {
                if (!LivingHopperFunction.isLivingHopper(ctx.getItem(slot))) continue;
            }
            filterOf[slot] = buildForSlot(slot, ctx, containerSize, sourceOf, targetOf, filterOf, new HashSet<>());
        }

        return filterOf;
    }

    private static FilterData buildForSlot(int mySlot, ContainerContext ctx,
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
        filterOf[slot] = buildForSlot(slot, ctx, containerSize, sourceOf, targetOf, filterOf, building);
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
}