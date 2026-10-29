package com.qiqi.li.client.render;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.qiqi.li.client.mixin.LivingItemSlotWrapperAccessor;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 每个容器界面的「活地图布局组」缓存。
 *
 * <p>2026-10-29 从 {@code AbstractContainerScreenMixin} 抽出：地图的<b>渲染</b>已挪到
 * {@code ContainerScreenEvent.Render.Foreground}（见 {@link LivingContainerOverlay}），
 * 而<b>命中判定</b>（右键传送、tooltip 抑制）仍留在 Mixin 里 ⇒ 两边必须读同一份布局，
 * 否则「看得见的地方点不中 / 点得中的地方看不见」。</p>
 *
 * <p>状态按界面实例缓存（{@link WeakHashMap}，键是界面本身），界面关闭即自动回收；
 * 缓存有效性由槽位内容哈希判定（见 {@link #contentHash}）。</p>
 */
public final class LivingMapGroups {

    private record Entry(int hash, List<LivingMapLayout.MapGroup> groups) {}

    /** 界面 → 该界面的布局组。键是界面实例，界面被丢弃后条目自然消失。 */
    private static final Map<AbstractContainerScreen<?>, Entry> CACHE = new WeakHashMap<>();

    private LivingMapGroups() {}

    /** 当前界面的布局组（内容变了才重扫）。 */
    public static List<LivingMapLayout.MapGroup> of(AbstractContainerScreen<?> screen) {
        int hash = contentHash(screen);
        Entry cached = CACHE.get(screen);
        if (cached != null && cached.hash() == hash) {
            return cached.groups();
        }
        List<LivingMapLayout.MapGroup> groups = LivingMapLayout.scan(screen.getMenu().slots);
        CACHE.put(screen, new Entry(hash, groups));
        return groups;
    }

    /** 丢掉某界面的缓存（界面 init / 关闭时调，避免复用旧界面的扫描结果）。 */
    public static void invalidate(AbstractContainerScreen<?> screen) {
        CACHE.remove(screen);
    }

    /**
     * 只把「可能是地图」的槽位纳入哈希：位置 + 物品类型。
     *
     * <p>数量 / 地图内容不进哈希——布局只由「哪一格是活地图、哪一格是空地图」决定，
     * 地图画到哪了不影响分组，进哈希只会每帧重扫。</p>
     */
    private static int contentHash(AbstractContainerScreen<?> screen) {
        int h = 1;
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (LivingItemManager.isLivingItem(stack) && (stack.is(Items.FILLED_MAP) || stack.is(Items.MAP))) {
                h = 31 * h + slot.index;
                h = 31 * h + slot.x;
                h = 31 * h + slot.y;
                h = 31 * h + stack.getItem().hashCode();
            }
        }
        return h;
    }

    /** 该槽属于哪个地图组（没有则 null）。 */
    public static LivingMapLayout.MapGroup findGroupBySlot(AbstractContainerScreen<?> screen, Slot slot) {
        for (LivingMapLayout.MapGroup group : of(screen)) {
            for (int idx : group.slotIndices()) {
                if (screen.getMenu().slots.get(idx) == slot) {
                    return group;
                }
            }
        }
        return null;
    }

    /**
     * 客户端槽位 → 服务端槽位号。
     *
     * <p>活箱子等界面会把原槽位包一层（{@link LivingItemSlotWrapperAccessor}），
     * 发包必须用被包裹的原始槽号，否则服务端对不上。</p>
     */
    public static int resolveServerSlotIndex(Slot slot) {
        if (slot instanceof LivingItemSlotWrapperAccessor accessor) {
            return accessor.living_item$getWrappedTarget().index;
        }
        return slot.index;
    }

    /** 同上，按客户端槽位下标查。 */
    public static int resolveServerSlotIndex(AbstractContainerScreen<?> screen, int clientSlotIndex) {
        if (clientSlotIndex >= 0 && clientSlotIndex < screen.getMenu().slots.size()) {
            return resolveServerSlotIndex(screen.getMenu().slots.get(clientSlotIndex));
        }
        return clientSlotIndex;
    }
}
