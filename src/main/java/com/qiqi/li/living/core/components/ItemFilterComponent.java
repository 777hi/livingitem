package com.qiqi.li.living.core.components;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingHopperFunction;

/**
 * 物品过滤组件 — 活漏斗的黑白名单过滤系统
 *
 * <h3>过滤模式（由活漏斗堆叠数量决定）</h3>
 * <ul>
 *   <li>堆叠1 → ID模式：仅比较物品ID</li>
 *   <li>堆叠2 → NBT模式：比较物品ID + DataComponents</li>
 *   <li>堆叠3 → Tag模式：比较物品ID + DataComponents + 标签</li>
 * </ul>
 *
 * <h3>优先级判定模型（田忌赛马）</h3>
 * <p>涵盖范围越小 → 优先级越高：</p>
 * <ul>
 *   <li>NBT级(3)：仅匹配特定NBT变体，如 "diamond_sword@12345"</li>
 *   <li>ID级(2)：匹配该物品所有变体，如 "diamond_sword"</li>
 *   <li>Tag级(1)：匹配标签下所有物品，如 "#minecraft:swords"</li>
 * </ul>
 * <p>判定规则：高优先级胜；同优先级黑名单胜；仅一方匹配则该方决定结果</p>
 *
 * <h3>遮蔽机制</h3>
 * <p>同一列表中，NBT级条目会遮蔽ID级条目。例如白名单有 "sword@123"(NBT级)，
 * 则 "sword"(ID级) 不再被视为白名单匹配——因为白名单已明确表示"只要这个特定变体"。</p>
 *
 * <h3>链式传递</h3>
 * <p>沿活漏斗链逐tick传播一跳。继承时无条件传递所有级别数据（ID/NBT/Tag），
 * 不因自身mode丢弃邻居已扫描出的高精度数据。</p>
 */
public class ItemFilterComponent implements ILivingComponent {

    public static final String ID = "item_filter";

    // --- NBT键：ID级数据 ---
    private static final String KEY_BLACKLIST = "blacklist";
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_BLACKLIST_SLOTS = "blacklist_slots";
    private static final String KEY_WHITELIST_SLOTS = "whitelist_slots";

    // --- NBT键：NBT级数据（compositeKey = "itemId@componentsHash"） ---
    private static final String KEY_BL_COMPOSITES = "bl_comp";
    private static final String KEY_WL_COMPOSITES = "wl_comp";

    // --- NBT键：Tag级数据（标签路径字符串，如 "minecraft:swords"） ---
    private static final String KEY_BL_TAGS = "bl_tags";
    private static final String KEY_WL_TAGS = "wl_tags";

    // --- NBT键：Tag级槽位映射 ---
    private static final String KEY_BL_TAG_SLOTS = "bl_tag_slots";
    private static final String KEY_WL_TAG_SLOTS = "wl_tag_slots";

    // 过滤模式：由活漏斗堆叠数量动态决定，不持久化
    public static final int MODE_ID = 1;
    public static final int MODE_COMPONENT = 2;
    public static final int MODE_TAG = 3;

    // 优先级：涵盖范围越小 → 优先级越高
    private static final int PRIORITY_NBT = 3;
    private static final int PRIORITY_ID = 2;
    private static final int PRIORITY_TAG = 1;

    @Override
    public String getComponentId() { return ID; }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        state.putList(KEY_BLACKLIST, new ListTag());
        state.putList(KEY_WHITELIST, new ListTag());
        state.putList(KEY_BLACKLIST_SLOTS, new ListTag());
        state.putList(KEY_WHITELIST_SLOTS, new ListTag());
        state.putList(KEY_BL_COMPOSITES, new ListTag());
        state.putList(KEY_WL_COMPOSITES, new ListTag());
        state.putList(KEY_BL_TAGS, new ListTag());
        state.putList(KEY_WL_TAGS, new ListTag());
        state.putList(KEY_BL_TAG_SLOTS, new ListTag());
        state.putList(KEY_WL_TAG_SLOTS, new ListTag());
        return state;
    }

    /**
     * 根据活漏斗堆叠数量决定过滤模式
     * 堆叠1=ID模式, 堆叠2=NBT模式, 堆叠3=Tag模式
     */
    private static int normalizeMode(int stackCount) {
        if (stackCount == 2) return MODE_COMPONENT;
        if (stackCount == 3) return MODE_TAG;
        return MODE_ID;
    }

    /**
     * 生成NBT级复合键："itemId@componentsHashCode"
     * 用于区分同ID不同NBT的物品变体
     */
    private static String getCompositeKey(ItemStack stack) {
        return getItemId(stack) + "@" + stack.getComponents().hashCode();
    }

    /** 收集物品所属的所有标签路径（如 "minecraft:swords"） */
    private static Set<String> collectItemTags(ItemStack stack) {
        Set<String> tags = new HashSet<>();
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem());
        holder.tags().forEach(tagKey -> tags.add(tagKey.location().toString()));
        return tags;
    }

    /**
     * tick扫描时的临时数据容器
     * 包含所有级别的过滤数据 + 槽位映射（用于tooltip显示来源）
     */
    private static class FilterData {
        Set<String> blacklist = new HashSet<>();
        Map<String, Integer> blacklistSlots = new HashMap<>();
        Set<String> whitelist = new HashSet<>();
        Map<String, Integer> whitelistSlots = new HashMap<>();
        Set<String> blComposites = new HashSet<>();
        Set<String> wlComposites = new HashSet<>();
        Set<String> blTags = new HashSet<>();
        Set<String> wlTags = new HashSet<>();
        Map<String, Integer> blTagSlots = new HashMap<>();
        Map<String, Integer> wlTagSlots = new HashMap<>();
    }

    /**
     * 过滤判定时的只读数据容器
     * 从ComponentState一次性加载6个数据集，避免重复反序列化
     */
    private static class FilterSets {
        final Set<String> blacklist;
        final Set<String> whitelist;
        final Set<String> blComposites;
        final Set<String> wlComposites;
        final Set<String> blTags;
        final Set<String> wlTags;

        FilterSets(ComponentState state) {
            blacklist = loadItemSet(state, KEY_BLACKLIST);
            whitelist = loadItemSet(state, KEY_WHITELIST);
            blComposites = loadItemSet(state, KEY_BL_COMPOSITES);
            wlComposites = loadItemSet(state, KEY_WL_COMPOSITES);
            blTags = loadItemSet(state, KEY_BL_TAGS);
            wlTags = loadItemSet(state, KEY_WL_TAGS);
        }

        boolean hasWhitelistRules() {
            return !whitelist.isEmpty() || !wlComposites.isEmpty() || !wlTags.isEmpty();
        }

        boolean hasAnyRules() {
            return !blacklist.isEmpty() || hasWhitelistRules()
                    || !blComposites.isEmpty() || !blTags.isEmpty();
        }
    }

    /**
     * 每tick扫描容器，构建黑白名单
     *
     * 规则：
     * - 邻居活漏斗的target指向我 → 邻居的source物品 = 我的黑名单
     * - 邻居活漏斗的source指向我 → 邻居的target物品 = 我的白名单
     * - 非我方向的邻居活漏斗 → 继承其过滤数据（链式传递）
     *
     * 扫描精度由邻居活漏斗的堆叠数决定（而非自身），因为堆叠数的语义是
     * "别人扫描我方向时应使用的精度"。链式继承则无条件传递所有级别数据。
     */
    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        ContainerContext container = ctx.containerCtx();
        ContainerSnapshot snapshot = container.getSnapshot();
        if (snapshot == null) return;

        int containerSize = snapshot.getContainerSize();
        int[] sourceOf = snapshot.getSourceOf();
        int[] targetOf = snapshot.getTargetOf();

        FilterData fd = new FilterData();

        // 第一轮：扫描target指向我的邻居 → 构建黑名单
        // 使用邻居活漏斗的mode（堆叠数），而非自身mode
        for (int slot = 0; slot < containerSize; slot++) {
            if (targetOf[slot] == hostSlot) {
                int srcSlot = sourceOf[slot];
                if (srcSlot >= 0) {
                    ItemStack srcItem = container.getItem(srcSlot);
                    if (!srcItem.isEmpty() && !LivingHopperFunction.isLivingHopper(srcItem)) {
                        ItemStack neighborStack = container.getItem(slot);
                        int neighborMode = LivingHopperFunction.isLivingHopper(neighborStack)
                                ? normalizeMode(neighborStack.getCount()) : MODE_ID;

                        String itemId = getItemId(srcItem);
                        fd.blacklist.add(itemId);
                        fd.blacklistSlots.putIfAbsent(itemId, slot);
                        if (neighborMode == MODE_COMPONENT) {
                            fd.blComposites.add(getCompositeKey(srcItem));
                        }
                        if (neighborMode == MODE_TAG) {
                            for (String tag : collectItemTags(srcItem)) {
                                if (fd.blTags.add(tag)) {
                                    fd.blTagSlots.put(tag, slot);
                                }
                            }
                        }
                    }
                }
                // 非我方向的邻居活漏斗 → 链式继承
                if (sourceOf[hostSlot] != slot && targetOf[hostSlot] != slot) {
                    inheritFilter(container, slot, fd);
                }
            }
        }

        // 第二轮：扫描source指向我的邻居 → 构建白名单
        // 使用邻居活漏斗的mode（堆叠数），而非自身mode
        for (int slot = 0; slot < containerSize; slot++) {
            if (sourceOf[slot] == hostSlot) {
                int tgtSlot = targetOf[slot];
                if (tgtSlot >= 0) {
                    ItemStack tgtItem = container.getItem(tgtSlot);
                    if (!tgtItem.isEmpty() && !LivingHopperFunction.isLivingHopper(tgtItem)) {
                        ItemStack neighborStack = container.getItem(slot);
                        int neighborMode = LivingHopperFunction.isLivingHopper(neighborStack)
                                ? normalizeMode(neighborStack.getCount()) : MODE_ID;

                        String itemId = getItemId(tgtItem);
                        fd.whitelist.add(itemId);
                        fd.whitelistSlots.putIfAbsent(itemId, slot);
                        if (neighborMode == MODE_COMPONENT) {
                            fd.wlComposites.add(getCompositeKey(tgtItem));
                        }
                        if (neighborMode == MODE_TAG) {
                            for (String tag : collectItemTags(tgtItem)) {
                                if (fd.wlTags.add(tag)) {
                                    fd.wlTagSlots.put(tag, slot);
                                }
                            }
                        }
                    }
                }
                // 非我方向的邻居活漏斗 → 链式继承
                if (sourceOf[hostSlot] != slot && targetOf[hostSlot] != slot) {
                    inheritFilter(container, slot, fd);
                }
            }
        }

        saveItemSet(state, KEY_BLACKLIST, fd.blacklist);
        saveItemSet(state, KEY_WHITELIST, fd.whitelist);
        saveSlotMap(state, KEY_BLACKLIST_SLOTS, fd.blacklist, fd.blacklistSlots);
        saveSlotMap(state, KEY_WHITELIST_SLOTS, fd.whitelist, fd.whitelistSlots);
        saveItemSet(state, KEY_BL_COMPOSITES, fd.blComposites);
        saveItemSet(state, KEY_WL_COMPOSITES, fd.wlComposites);
        saveItemSet(state, KEY_BL_TAGS, fd.blTags);
        saveItemSet(state, KEY_WL_TAGS, fd.wlTags);
        saveSlotMap(state, KEY_BL_TAG_SLOTS, fd.blTags, fd.blTagSlots);
        saveSlotMap(state, KEY_WL_TAG_SLOTS, fd.wlTags, fd.wlTagSlots);
    }

    /**
     * 从邻居活漏斗继承过滤数据（链式传递）
     *
     * 无条件继承所有级别数据（ID/NBT/Tag），不因自身mode丢弃邻居已扫描出的高精度数据。
     * 原因：mode只控制自身扫描精度，邻居已经付出了扫描成本，丢弃只会让链式传递精度逐跳退化。
     */
    private void inheritFilter(ContainerContext container, int slot, FilterData fd) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) return;

        if (!LivingHopperFunction.isLivingHopper(stack)) return;

        CompoundTag funcTag = LivingItemManager.getFunctionData(stack, LivingHopperFunction.ID);
        if (funcTag == null || !funcTag.contains(ID)) return;

        ComponentState neighborState = ComponentState.fromNBT(funcTag.getCompound(ID));

        // 继承ID级数据 + 槽位映射
        Set<String> nBlacklist = loadItemSet(neighborState, KEY_BLACKLIST);
        Set<String> nWhitelist = loadItemSet(neighborState, KEY_WHITELIST);
        Map<String, Integer> nBlacklistSlots = loadSlotMap(neighborState, KEY_BLACKLIST_SLOTS);
        Map<String, Integer> nWhitelistSlots = loadSlotMap(neighborState, KEY_WHITELIST_SLOTS);

        for (String itemId : nBlacklist) {
            if (fd.blacklist.add(itemId)) {
                Integer origSlot = nBlacklistSlots.get(itemId);
                fd.blacklistSlots.put(itemId, origSlot != null ? origSlot : -1);
            }
        }
        for (String itemId : nWhitelist) {
            if (fd.whitelist.add(itemId)) {
                Integer origSlot = nWhitelistSlots.get(itemId);
                fd.whitelistSlots.put(itemId, origSlot != null ? origSlot : -1);
            }
        }

        // 无条件继承NBT级和Tag级数据
        fd.blComposites.addAll(loadItemSet(neighborState, KEY_BL_COMPOSITES));
        fd.wlComposites.addAll(loadItemSet(neighborState, KEY_WL_COMPOSITES));
        Map<String, Integer> nBlTagSlots = loadSlotMap(neighborState, KEY_BL_TAG_SLOTS, KEY_BL_TAGS);
        for (String tag : loadItemSet(neighborState, KEY_BL_TAGS)) {
            if (fd.blTags.add(tag)) {
                fd.blTagSlots.put(tag, nBlTagSlots.getOrDefault(tag, -1));
            }
        }
        Map<String, Integer> nWlTagSlots = loadSlotMap(neighborState, KEY_WL_TAG_SLOTS, KEY_WL_TAGS);
        for (String tag : loadItemSet(neighborState, KEY_WL_TAGS)) {
            if (fd.wlTags.add(tag)) {
                fd.wlTagSlots.put(tag, nWlTagSlots.getOrDefault(tag, -1));
            }
        }
    }

    /**
     * Tooltip显示：按优先级从高到低排列每个级别的过滤条目
     * NBT条目 → ID条目 → Tag条目，每个条目标注级别后缀
     * 应用遮蔽机制：NBT级条目存在时，同itemId的ID级条目不再显示
     */
    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        FilterSets fs = new FilterSets(state);
        Map<String, Integer> idSlots = loadSlotMap(state, KEY_BLACKLIST_SLOTS);
        idSlots.putAll(loadSlotMap(state, KEY_WHITELIST_SLOTS));
        Map<String, Integer> tagSlots = loadSlotMap(state, KEY_BL_TAG_SLOTS, KEY_BL_TAGS);
        tagSlots.putAll(loadSlotMap(state, KEY_WL_TAG_SLOTS, KEY_WL_TAGS));

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

    /**
     * 渲染一组过滤条目（NBT→ID→Tag），应用遮蔽机制
     */
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

    /** 根据物品ID获取显示名称，失败则回退到原始ID字符串 */
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

    // ==================== 过滤判定 API ====================

    /** 判断物品是否允许通过（带完整NBT的ItemStack版本） */
    public static boolean allows(ComponentState filterState, ItemStack item) {
        if (filterState == null || item.isEmpty()) return true;
        return allowsByPriority(filterState, item);
    }

    /**
     * 核心优先级判定逻辑（田忌赛马模型）
     *
     * 1. 分别计算白名单和黑名单对该物品的匹配优先级
     * 2. 双方都匹配 → 高优先级胜（wlPriority > blPriority 则放行）
     * 3. 仅一方匹配 → 匹配方决定结果
     * 4. 双方都不匹配 → 有白名单规则则拒绝，否则放行
     */
    private static boolean allowsByPriority(ComponentState filterState, ItemStack item) {
        FilterSets fs = new FilterSets(filterState);
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

    /**
     * 计算单侧（白名单或黑名单）对该物品的匹配优先级
     *
     * 遮蔽机制：NBT级条目会遮蔽ID级条目。
     * 例如白名单有 "sword@123"(NBT级)，则 "sword"(ID级) 不再匹配——
     * 因为白名单已明确表示"只要这个特定变体"，ID级的宽泛许可被更精确的NBT级规则取代。
     *
     * @return 0=不匹配, PRIORITY_TAG=1, PRIORITY_ID=2, PRIORITY_NBT=3
     */
    private static int calcMatchPriority(String itemId, String composite, ItemStack item,
            Set<String> idSet, Set<String> compositeSet, Set<String> tagSet) {
        // NBT级：精确匹配compositeKey
        if (compositeSet.contains(composite)) return PRIORITY_NBT;

        // ID级：仅在该ID没有NBT级条目时才匹配（遮蔽机制）
        boolean hasNbtForId = hasNbtEntriesForId(compositeSet, itemId);
        if (!hasNbtForId && idSet.contains(itemId)) return PRIORITY_ID;

        // Tag级：匹配物品所属标签
        if (matchesAnyTag(item, tagSet)) return PRIORITY_TAG;

        return 0;
    }

    /** 检查compositeSet中是否存在指定itemId的NBT级条目（用于遮蔽判断） */
    private static boolean hasNbtEntriesForId(Set<String> composites, String itemId) {
        String prefix = itemId + "@";
        for (String c : composites) {
            if (c.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 检查物品是否匹配tagSet中的任一标签 */
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

    /**
     * 判断物品类型是否允许通过（仅itemId版本，无NBT信息）
     * 用于活箱子提取时的预查，此时只有物品ID可用
     */
    public static boolean allowsItemType(ComponentState filterState, String itemId) {
        if (filterState == null) return true;

        FilterSets fs = new FilterSets(filterState);

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

    /** 根据itemId查找其所属标签，计算Tag级匹配优先级（无ItemStack版本） */
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

    /** 检查是否存在任何过滤规则 */
    public static boolean hasFilterRules(ComponentState filterState) {
        if (filterState == null) return false;
        return new FilterSets(filterState).hasAnyRules();
    }

    // ==================== 工具方法 ====================

    private static String getItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void saveItemSet(ComponentState state, String key, Set<String> items) {
        ListTag list = new ListTag();
        for (String item : items) {
            list.add(StringTag.valueOf(item));
        }
        state.putList(key, list);
    }

    private static void saveSlotMap(ComponentState state, String key, Set<String> items, Map<String, Integer> slots) {
        ListTag list = new ListTag();
        for (String item : items) {
            Integer slot = slots.get(item);
            list.add(IntTag.valueOf(slot != null ? slot : -1));
        }
        state.putList(key, list);
    }

    private static Set<String> loadItemSet(ComponentState state, String key) {
        Set<String> result = new HashSet<>();
        ListTag list = state.getList(key, Tag.TAG_STRING);
        if (list == null) return result;
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getString(i));
        }
        return result;
    }

    private static Map<String, Integer> loadSlotMap(ComponentState state, String key) {
        String itemListKey = key.equals(KEY_BLACKLIST_SLOTS) ? KEY_BLACKLIST : KEY_WHITELIST;
        return loadSlotMap(state, key, itemListKey);
    }

    private static Map<String, Integer> loadSlotMap(ComponentState state, String slotKey, String itemListKey) {
        Map<String, Integer> result = new HashMap<>();
        ListTag list = state.getList(slotKey, Tag.TAG_INT);
        if (list == null) return result;
        ListTag itemList = state.getList(itemListKey, Tag.TAG_STRING);
        if (itemList == null) return result;
        int size = Math.min(itemList.size(), list.size());
        for (int i = 0; i < size; i++) {
            int slot = ((net.minecraft.nbt.IntTag) list.get(i)).getAsInt();
            if (slot >= 0) {
                result.put(itemList.getString(i), slot);
            }
        }
        return result;
    }
}