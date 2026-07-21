package com.qiqi.li.living.core.components;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
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
 * 物品过滤组件 (Item Filter Component)
 *
 * 通过相邻活漏斗的输入/输出槽位物品，自动为当前活物品构建黑白名单过滤规则。
 * 设计为通用组件，任何活物品（活漏斗、活熔炉等）均可使用。
 *
 * <h2>过滤规则</h2>
 * <ol>
 *   <li><strong>黑名单</strong>：邻居活漏斗的 target 指向我 → 邻居的 source 物品 = 我的黑名单</li>
 *   <li><strong>白名单</strong>：邻居活漏斗的 source 指向我 → 邻居的 target 物品 = 我的白名单</li>
 * </ol>
 *
 * <h2>链式传递（传输模型）</h2>
 * 名单像物品一样通过漏斗链逐跳传播。每个漏斗只关心自己的直接邻居，
 * 并继承邻居已计算好的名单，实现每 tick 传播一跳：
 * <pre>
 * Tick 1: A 计算自己的名单 → B 从 A 继承 → C 从 B 继承（此时 B 的名单还是旧的）
 * Tick 2: B 已更新名单 → C 从 B 继承（拿到完整名单）
 * </pre>
 * 链稳定后，所有漏斗持有完整名单。
 *
 * <h2>判断逻辑</h2>
 * <pre>
 * allows(item):
 *   1. 黑名单非空 且 item 在黑名单中 → 拒绝
 *   2. 白名单非空 且 item 不在白名单中 → 拒绝
 *   3. 其他情况 → 允许
 * </pre>
 *
 * <h2>使用方式</h2>
 * 在 {@link ItemTransferComponent} 等传输组件中调用 {@link #allows(ComponentState, ItemStack)} 检查。
 * 如果该组件未在配置中注册，{@link ComponentContext#getComponentState} 返回 null，自动跳过过滤。
 */
public class ItemFilterComponent implements ILivingComponent {

    public static final String ID = "item_filter";

    private static final String KEY_BLACKLIST = "blacklist";
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_BLACKLIST_SLOTS = "blacklist_slots";
    private static final String KEY_WHITELIST_SLOTS = "whitelist_slots";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        state.putList(KEY_BLACKLIST, new ListTag());
        state.putList(KEY_WHITELIST, new ListTag());
        state.putList(KEY_BLACKLIST_SLOTS, new ListTag());
        state.putList(KEY_WHITELIST_SLOTS, new ListTag());
        return state;
    }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        ContainerContext container = ctx.containerCtx();
        ContainerSnapshot snapshot = container.getSnapshot();
        if (snapshot == null) return;

        int containerSize = snapshot.getContainerSize();
        int[] sourceOf = snapshot.getSourceOf();
        int[] targetOf = snapshot.getTargetOf();

        Set<String> blacklist = new HashSet<>();
        Map<String, Integer> blacklistSlots = new HashMap<>();
        Set<String> whitelist = new HashSet<>();
        Map<String, Integer> whitelistSlots = new HashMap<>();

        for (int slot = 0; slot < containerSize; slot++) {
            if (targetOf[slot] == hostSlot) {
                // 邻居 source 物品 = 我的黑名单
                int srcSlot = sourceOf[slot];
                if (srcSlot >= 0) {
                    ItemStack srcItem = container.getItem(srcSlot);
                    if (!srcItem.isEmpty() && !LivingHopperFunction.isLivingHopper(srcItem)) {
                        String itemId = getItemId(srcItem);
                        blacklist.add(itemId);
                        blacklistSlots.putIfAbsent(itemId, slot);
                    }
                }
                // 继承邻居的全部名单（链式传递：每 tick 传播一跳）
                inheritFilter(container, slot, blacklist, blacklistSlots, whitelist, whitelistSlots);
            }
        }

        for (int slot = 0; slot < containerSize; slot++) {
            if (sourceOf[slot] == hostSlot) {
                // 邻居 target 物品 = 我的白名单
                int tgtSlot = targetOf[slot];
                if (tgtSlot >= 0) {
                    ItemStack tgtItem = container.getItem(tgtSlot);
                    if (!tgtItem.isEmpty() && !LivingHopperFunction.isLivingHopper(tgtItem)) {
                        String itemId = getItemId(tgtItem);
                        whitelist.add(itemId);
                        whitelistSlots.putIfAbsent(itemId, slot);
                    }
                }
                // 继承邻居的全部名单（链式传递：每 tick 传播一跳）
                inheritFilter(container, slot, blacklist, blacklistSlots, whitelist, whitelistSlots);
            }
        }

        saveItemSet(state, KEY_BLACKLIST, blacklist);
        saveItemSet(state, KEY_WHITELIST, whitelist);
        saveSlotMap(state, KEY_BLACKLIST_SLOTS, blacklist, blacklistSlots);
        saveSlotMap(state, KEY_WHITELIST_SLOTS, whitelist, whitelistSlots);
    }

    /**
     * 从邻居活漏斗的 NBT 中继承其全部已计算的名单（黑白名单都拿）。
     * 名单像物品一样，每 tick 沿漏斗链传播一跳。
     * 继承来的物品来源槽位标记为 -1（未知原始槽位）。
     */
    private void inheritFilter(ContainerContext container, int slot,
                               Set<String> blacklist, Map<String, Integer> blacklistSlots,
                               Set<String> whitelist, Map<String, Integer> whitelistSlots) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty() || !LivingHopperFunction.isLivingHopper(stack)) return;

        CompoundTag funcTag = LivingItemManager.getFunctionData(stack, LivingHopperFunction.ID);
        if (funcTag == null || !funcTag.contains(ID)) return;

        ComponentState neighborState = ComponentState.fromNBT(funcTag.getCompound(ID));
        Set<String> nBlacklist = loadItemSet(neighborState, KEY_BLACKLIST);
        Set<String> nWhitelist = loadItemSet(neighborState, KEY_WHITELIST);
        Map<String, Integer> nBlacklistSlots = loadSlotMap(neighborState, KEY_BLACKLIST_SLOTS);
        Map<String, Integer> nWhitelistSlots = loadSlotMap(neighborState, KEY_WHITELIST_SLOTS);

        for (String itemId : nBlacklist) {
            if (blacklist.add(itemId)) {
                Integer origSlot = nBlacklistSlots.get(itemId);
                blacklistSlots.put(itemId, origSlot != null ? origSlot : -1);
            }
        }
        for (String itemId : nWhitelist) {
            if (whitelist.add(itemId)) {
                Integer origSlot = nWhitelistSlots.get(itemId);
                whitelistSlots.put(itemId, origSlot != null ? origSlot : -1);
            }
        }
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        Set<String> blacklist = loadItemSet(state, KEY_BLACKLIST);
        Set<String> whitelist = loadItemSet(state, KEY_WHITELIST);
        Map<String, Integer> blacklistSlots = loadSlotMap(state, KEY_BLACKLIST_SLOTS);
        Map<String, Integer> whitelistSlots = loadSlotMap(state, KEY_WHITELIST_SLOTS);

        if (!whitelist.isEmpty()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.filter.whitelist"));
            for (String itemId : whitelist) {
                Integer slot = whitelistSlots.get(itemId);
                MutableComponent line = Component.literal("  - ").append(getItemDisplayName(itemId));
                if (slot != null && slot >= 0) {
                    line.append(Component.literal(" (§7槽" + (slot + 1) + "§r)"));
                }
                tooltipAdder.accept(line.withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }

        if (!blacklist.isEmpty()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.filter.blacklist"));
            for (String itemId : blacklist) {
                Integer slot = blacklistSlots.get(itemId);
                MutableComponent line = Component.literal("  - ").append(getItemDisplayName(itemId));
                if (slot != null && slot >= 0) {
                    line.append(Component.literal(" (§7槽" + (slot + 1) + "§r)"));
                }
                tooltipAdder.accept(line.withStyle(net.minecraft.ChatFormatting.GRAY));
            }
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

    /**
     * 检查物品是否被当前过滤器允许通过。
     *
     * 判断逻辑：
     * <ol>
     *   <li>黑名单非空 且 item 在黑名单中 → 拒绝</li>
     *   <li>白名单非空 且 item 不在白名单中 → 拒绝</li>
     *   <li>其他情况 → 允许</li>
     * </ol>
     *
     * @param filterState 过滤器状态（可能为 null，表示无过滤）
     * @param item 要检查的物品
     * @return true 允许通过，false 被拦截
     */
    public static boolean allows(ComponentState filterState, ItemStack item) {
        if (filterState == null || item.isEmpty()) return true;

        String itemId = getItemId(item);
        return allowsItemType(filterState, itemId);
    }

    /**
     * 判断指定物品类型是否允许通过过滤器。
     *
     * <p>用于活末影箱路由查询等只有物品类型字符串而无 ItemStack 实体的场景。</p>
     *
     * @param filterState 过滤组件状态（可为 null，表示不过滤）
     * @param itemId 物品注册名（如 "minecraft:diamond"）
     * @return true 允许通过，false 被拦截
     */
    public static boolean allowsItemType(ComponentState filterState, String itemId) {
        if (filterState == null) return true;

        Set<String> blacklist = loadItemSet(filterState, KEY_BLACKLIST);
        Set<String> whitelist = loadItemSet(filterState, KEY_WHITELIST);

        if (!blacklist.isEmpty() && blacklist.contains(itemId)) return false;
        if (!whitelist.isEmpty() && !whitelist.contains(itemId)) return false;

        return true;
    }

    /**
     * 检查过滤器是否配置了任何规则（黑名单或白名单非空）。
     *
     * <p>活末影箱 Pull 端要求：没有黑白名单的漏斗不允许从活末影箱提取物品。</p>
     *
     * @param filterState 过滤组件状态（可为 null）
     * @return true 有至少一条过滤规则
     */
    public static boolean hasFilterRules(ComponentState filterState) {
        if (filterState == null) return false;
        Set<String> blacklist = loadItemSet(filterState, KEY_BLACKLIST);
        Set<String> whitelist = loadItemSet(filterState, KEY_WHITELIST);
        return !blacklist.isEmpty() || !whitelist.isEmpty();
    }

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
        Map<String, Integer> result = new HashMap<>();
        ListTag list = state.getList(key, Tag.TAG_INT);
        if (list == null) return result;
        ListTag itemList = state.getList(
            key.equals(KEY_BLACKLIST_SLOTS) ? KEY_BLACKLIST : KEY_WHITELIST,
            Tag.TAG_STRING);
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