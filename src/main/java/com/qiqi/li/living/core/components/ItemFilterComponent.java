package com.qiqi.li.living.core.components;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemFilterComponent {

    public static final String ID = "item_filter";

    public static final int MODE_ID = 1;
    public static final int MODE_COMPONENT = 2;
    public static final int MODE_TAG = 3;

    private static final int PRIORITY_NBT = 3;
    private static final int PRIORITY_ID = 2;
    private static final int PRIORITY_TAG = 1;

    private ItemFilterComponent() {}

    public static int normalizeMode(int stackCount) {
        if (stackCount == 2) return MODE_COMPONENT;
        if (stackCount == 3) return MODE_TAG;
        return MODE_ID;
    }

    public static String getCompositeKey(ItemStack stack) {
        return getItemId(stack) + "@" + stack.getComponents().hashCode();
    }

    public static Set<String> collectItemTags(ItemStack stack) {
        Set<String> tags = new HashSet<>();
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem());
        holder.tags().forEach(tagKey -> tags.add(tagKey.location().toString()));
        return tags;
    }

    public static boolean allows(com.qiqi.li.living.data.FilterData filterData, ItemStack item) {
        if (filterData == null || item.isEmpty()) return true;
        if (filterData.equals(com.qiqi.li.living.data.FilterData.EMPTY)) return true;
        return allowsByPriority(new FilterSets(filterData), item);
    }

    public static boolean allowsItemType(com.qiqi.li.living.data.FilterData filterData, String itemId) {
        if (filterData == null) return true;
        if (filterData.equals(com.qiqi.li.living.data.FilterData.EMPTY)) return true;
        return allowsItemTypeByPriority(new FilterSets(filterData), itemId);
    }

    public static boolean hasFilterRules(com.qiqi.li.living.data.FilterData filterData) {
        if (filterData == null) return false;
        if (filterData.equals(com.qiqi.li.living.data.FilterData.EMPTY)) return false;
        return new FilterSets(filterData).hasAnyRules();
    }

    public static void appendFilterTooltip(com.qiqi.li.living.data.FilterData filterData,
                                            Consumer<Component> tooltipAdder) {
        if (filterData == null || filterData.equals(com.qiqi.li.living.data.FilterData.EMPTY)) return;

        FilterSets fs = new FilterSets(filterData);
        Map<String, Integer> idSlots = listToSlotMap(filterData.blacklist(), filterData.blacklistSlots());
        idSlots.putAll(listToSlotMap(filterData.whitelist(), filterData.whitelistSlots()));
        Map<String, Integer> tagSlots = listToSlotMap(filterData.blTags(), filterData.blTagSlots());
        tagSlots.putAll(listToSlotMap(filterData.wlTags(), filterData.wlTagSlots()));

        boolean hasWhitelist = fs.hasWhitelistRules();
        boolean hasBlacklist = !fs.blacklist.isEmpty() || !fs.blComposites.isEmpty() || !fs.blTags.isEmpty();

        if (hasWhitelist) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.filter.whitelist"));
            appendFilterEntries(tooltipAdder, fs.whitelist, fs.wlComposites, fs.wlTags, idSlots, tagSlots);
        }

        if (hasBlacklist) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.filter.blacklist"));
            appendFilterEntries(tooltipAdder, fs.blacklist, fs.blComposites, fs.blTags, idSlots, tagSlots);
        }
    }

    // ==================== 内部实现 ====================

    private static class FilterSets {
        final Set<String> blacklist;
        final Set<String> whitelist;
        final Set<String> blComposites;
        final Set<String> wlComposites;
        final Set<String> blTags;
        final Set<String> wlTags;

        FilterSets(com.qiqi.li.living.data.FilterData data) {
            blacklist = new HashSet<>(data.blacklist());
            whitelist = new HashSet<>(data.whitelist());
            blComposites = new HashSet<>(data.blComposites());
            wlComposites = new HashSet<>(data.wlComposites());
            blTags = new HashSet<>(data.blTags());
            wlTags = new HashSet<>(data.wlTags());
        }

        boolean hasWhitelistRules() {
            return !whitelist.isEmpty() || !wlComposites.isEmpty() || !wlTags.isEmpty();
        }

        boolean hasAnyRules() {
            return !blacklist.isEmpty() || hasWhitelistRules()
                    || !blComposites.isEmpty() || !blTags.isEmpty();
        }
    }

    private static boolean allowsByPriority(FilterSets fs, ItemStack item) {
        String itemId = getItemId(item);
        String composite = getCompositeKey(item);

        int wlPriority = calcMatchPriority(itemId, composite, item,
                fs.whitelist, fs.wlComposites, fs.wlTags);
        int blPriority = calcMatchPriority(itemId, composite, item,
                fs.blacklist, fs.blComposites, fs.blTags);

        if (wlPriority > 0 && blPriority > 0) {
            return wlPriority > blPriority;
        }
        if (wlPriority > 0) return true;
        if (blPriority > 0) return false;

        return !fs.hasWhitelistRules();
    }

    private static int calcMatchPriority(String itemId, String composite, ItemStack item,
            Set<String> idSet, Set<String> compositeSet, Set<String> tagSet) {
        if (compositeSet.contains(composite)) return PRIORITY_NBT;

        boolean hasNbtForId = hasNbtEntriesForId(compositeSet, itemId);
        if (!hasNbtForId && idSet.contains(itemId)) return PRIORITY_ID;

        if (matchesAnyTag(item, tagSet)) return PRIORITY_TAG;

        return 0;
    }

    private static boolean allowsItemTypeByPriority(FilterSets fs, String itemId) {
        boolean wlHasNbtForId = hasNbtEntriesForId(fs.wlComposites, itemId);
        boolean blHasNbtForId = hasNbtEntriesForId(fs.blComposites, itemId);

        int wlPriority = 0;
        if (!wlHasNbtForId && fs.whitelist.contains(itemId)) wlPriority = PRIORITY_ID;
        if (wlPriority == 0) {
            wlPriority = calcTagPriorityForItemId(itemId, fs.wlTags);
        }

        int blPriority = 0;
        if (!blHasNbtForId && fs.blacklist.contains(itemId)) blPriority = PRIORITY_ID;
        if (blPriority == 0) {
            blPriority = calcTagPriorityForItemId(itemId, fs.blTags);
        }

        if (wlPriority > 0 && blPriority > 0) return wlPriority > blPriority;
        if (wlPriority > 0) return true;
        if (blPriority > 0) return false;

        return !fs.hasWhitelistRules();
    }

    private static boolean hasNbtEntriesForId(Set<String> composites, String itemId) {
        String prefix = itemId + "@";
        for (String c : composites) {
            if (c.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean matchesAnyTag(ItemStack item, Set<String> tagStrings) {
        if (tagStrings.isEmpty()) return false;
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item.getItem());
        for (String tagStr : tagStrings) {
            TagKey<Item> tagKey = TagKey.create(
                    BuiltInRegistries.ITEM.key(), ResourceLocation.parse(tagStr));
            if (holder.is(tagKey)) return true;
        }
        return false;
    }

    private static int calcTagPriorityForItemId(String itemId, Set<String> tagSet) {
        if (tagSet.isEmpty()) return 0;
        try {
            ResourceLocation rl = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.get(rl);
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
            for (String tagStr : tagSet) {
                TagKey<Item> tagKey = TagKey.create(
                        BuiltInRegistries.ITEM.key(), ResourceLocation.parse(tagStr));
                if (holder.is(tagKey)) return PRIORITY_TAG;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String getItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void appendFilterEntries(Consumer<Component> tooltipAdder,
            Set<String> idSet, Set<String> compositeSet, Set<String> tagSet,
            Map<String, Integer> idSlots, Map<String, Integer> tagSlots) {
        for (String composite : compositeSet) {
            int at = composite.indexOf('@');
            String itemId = at > 0 ? composite.substring(0, at) : composite;
            MutableComponent line = Component.literal("  - ").append(getItemDisplayName(itemId));
            Integer slot = idSlots.get(itemId);
            if (slot != null && slot >= 0) {
                line.append(Component.literal(" (\u00a77\u69fd" + (slot + 1) + "\u00a7r)"));
            }
            line.append(Component.literal(" \u00a7b[NBT]\u00a7r"));
            tooltipAdder.accept(line.withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        for (String itemId : idSet) {
            if (hasNbtEntriesForId(compositeSet, itemId)) continue;
            Integer slot = idSlots.get(itemId);
            MutableComponent line = Component.literal("  - ").append(getItemDisplayName(itemId));
            if (slot != null && slot >= 0) {
                line.append(Component.literal(" (\u00a77\u69fd" + (slot + 1) + "\u00a7r)"));
            }
            line.append(Component.literal(" \u00a7f[ID]\u00a7r"));
            tooltipAdder.accept(line.withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        for (String tag : tagSet) {
            MutableComponent line = Component.literal("  - \u00a7d#\u00a7r" + tag);
            Integer slot = tagSlots.get(tag);
            if (slot != null && slot >= 0) {
                line.append(Component.literal(" (\u00a77\u69fd" + (slot + 1) + "\u00a7r)"));
            }
            line.append(Component.literal(" \u00a7d[Tag]\u00a7r"));
            tooltipAdder.accept(line.withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    private static Component getItemDisplayName(String itemId) {
        try {
            ResourceLocation rl = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) {
                return new ItemStack(item).getDisplayName();
            }
        } catch (Exception ignored) {
        }
        return Component.literal(itemId);
    }

    private static Map<String, Integer> listToSlotMap(List<String> items, List<Integer> slots) {
        Map<String, Integer> map = new HashMap<>();
        int size = Math.min(items.size(), slots.size());
        for (int i = 0; i < size; i++) {
            int slot = slots.get(i);
            if (slot >= 0) map.put(items.get(i), slot);
        }
        return map;
    }
}