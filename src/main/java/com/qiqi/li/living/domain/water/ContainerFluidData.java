package com.qiqi.li.living.domain.water;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 容器级流体数据 —— 参照原版水流平地蔓延逻辑实现。
 *
 * 核心映射：
 * - ContainerFluidData = 一个区块的水流状态
 * - 槽位 = 方块位置
 * - FlowEntry(level=0, isSource=true) = 水源方块
 * - FlowEntry(level=1~7, isSource=false) = 流动水方块
 * - 无 FlowEntry = 空气
 * - 活物品 = 阻挡水流的方块
 * - 非活物品 = 水中的实体（不阻挡水流，被水流推动）
 *
 * 与原版一致的行为：
 * - 水源向4方向蔓延，level 递增，最远7格
 * - 每个 tick 通过 BFS 从所有水源重算流动状态
 * - 水源移除后，不可达的流动立即消失（简单实现）
 * - 多水源时，每个槽位取最近水源的 level
 * - 流动水记录 fromSlot（BFS 父节点），物品沿水流方向推动
 */
public class ContainerFluidData {

    public static final ContainerFluidData EMPTY = new ContainerFluidData() {
        @Override
        public void registerSource(int slot) { }
        @Override
        public void removeSource(int slot) { }
        @Override
        public void setLastTickTime(long time) { }
        @Override
        public void tick(ContainerContext ctx) { }
    };

    public static final int SOURCE_LEVEL = 0;
    public static final int MAX_FLOW_LEVEL = 7;
    public static final int FLOW_STEP_TICKS = 4;

    public static class FlowEntry {
        int level;
        boolean isSource;
        int fromSlot;

        public FlowEntry(int level, boolean isSource, int fromSlot) {
            this.level = level;
            this.isSource = isSource;
            this.fromSlot = fromSlot;
        }

        public int level() { return level; }
        public boolean isSource() { return isSource; }
        public int fromSlot() { return fromSlot; }
    }

    private final Map<Integer, FlowEntry> flows = new LinkedHashMap<>();
    private long lastTickTime;
    private int tickCounter;

    public Map<Integer, FlowEntry> getFlows() {
        return flows;
    }

    public boolean isEmpty() {
        return flows.isEmpty();
    }

    public long getLastTickTime() {
        return lastTickTime;
    }

    public void setLastTickTime(long time) {
        this.lastTickTime = time;
    }

    public void registerSource(int slot) {
        FlowEntry existing = flows.get(slot);
        if (existing != null) {
            existing.isSource = true;
            existing.level = SOURCE_LEVEL;
            existing.fromSlot = -1;
        } else {
            flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1));
        }
    }

    public void removeSource(int slot) {
        FlowEntry entry = flows.get(slot);
        if (entry != null) {
            entry.isSource = false;
        }
    }

    public void tick(ContainerContext ctx) {
        int containerSize = ctx.getSize();
        int width = ctx.getWidth();
        if (containerSize <= 0 || width <= 0) return;

        tickCounter++;

        recalculate(containerSize, width, ctx);

        if (tickCounter % FLOW_STEP_TICKS == 0) {
            pushItems(ctx, containerSize, width);
        }
    }

    /**
     * BFS 从所有水源重算流动状态。
     *
     * 类比原版：每个 tick 重新计算所有流动水的 level，
     * 确保水源增减、活物品放置/移除后流动状态立即更新。
     *
     * - 活物品阻挡水流（类比方块）
     * - 非活物品不阻挡水流（类比实体，水穿过）
     * - 每个槽位取最近水源的 level（多水源取最小值）
     */
    private void recalculate(int containerSize, int width, ContainerContext ctx) {
        Map<Integer, FlowEntry> newFlows = new LinkedHashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();

        for (var entry : flows.entrySet()) {
            if (entry.getValue().isSource) {
                int slot = entry.getKey();
                ItemStack item = ctx.getItem(slot);
                if (item.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(item)) {
                    newFlows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1));
                    queue.add(slot);
                }
            }
        }

        while (!queue.isEmpty()) {
            int slot = queue.poll();
            FlowEntry fe = newFlows.get(slot);
            if (fe.level >= MAX_FLOW_LEVEL) continue;

            int[] neighbors = ContainerContext.getNeighbors(slot, containerSize, width);
            for (int neighbor : neighbors) {
                if (newFlows.containsKey(neighbor)) continue;

                ItemStack item = ctx.getItem(neighbor);
                if (LivingItemManager.isLivingItem(item)) continue;

                int newLevel = fe.level + 1;
                newFlows.put(neighbor, new FlowEntry(newLevel, false, slot));
                queue.add(neighbor);
            }
        }

        flows.clear();
        flows.putAll(newFlows);
    }

    /**
     * 沿水流方向推动物品。
     *
     * 构建 BFS 水流树的下游映射（fromSlot → 子节点列表），
     * 物品被推到其下游子节点，自然跟随水流的弯曲路径。
     * 按 level 降序处理，外层先推，形成级联效果。
     * 支持堆叠：目标槽位有同类物品时合并。
     */
    private void pushItems(ContainerContext ctx, int containerSize, int width) {
        Map<Integer, List<Integer>> downstream = new HashMap<>();
        for (var entry : flows.entrySet()) {
            int slot = entry.getKey();
            FlowEntry fe = entry.getValue();
            if (fe.fromSlot >= 0) {
                downstream.computeIfAbsent(fe.fromSlot, k -> new ArrayList<>()).add(slot);
            }
        }

        List<Map.Entry<Integer, FlowEntry>> sorted = new ArrayList<>(flows.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().level, a.getValue().level));

        for (var entry : sorted) {
            int slot = entry.getKey();
            FlowEntry fe = entry.getValue();
            if (fe.isSource) continue;

            ItemStack item = ctx.getItem(slot);
            if (item.isEmpty() || LivingItemManager.isLivingItem(item)) continue;

            List<Integer> children = downstream.get(slot);
            if (children == null || children.isEmpty()) continue;

            for (int targetSlot : children) {
                if (targetSlot < 0 || targetSlot >= containerSize) continue;

                ItemStack targetItem = ctx.getItem(targetSlot);
                if (targetItem.isEmpty()) {
                    ItemStack moved = item.copy();
                    ctx.setItem(targetSlot, moved);
                    ctx.setItem(slot, ItemStack.EMPTY);
                    ctx.syncSlotToClients(targetSlot, moved);
                    ctx.syncSlotToClients(slot, ItemStack.EMPTY);
                    break;
                } else if (ItemStack.isSameItemSameComponents(item, targetItem)
                           && targetItem.getCount() < targetItem.getMaxStackSize()) {
                    int space = targetItem.getMaxStackSize() - targetItem.getCount();
                    int toAdd = Math.min(item.getCount(), space);
                    targetItem.grow(toAdd);
                    item.shrink(toAdd);
                    ctx.syncSlotToClients(targetSlot, targetItem);
                    if (item.isEmpty()) {
                        ctx.setItem(slot, ItemStack.EMPTY);
                        ctx.syncSlotToClients(slot, ItemStack.EMPTY);
                        break;
                    } else {
                        ctx.syncSlotToClients(slot, item);
                    }
                }
            }
        }
    }

    public Map<Integer, int[]> exportFlowData() {
        Map<Integer, int[]> map = new LinkedHashMap<>();
        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            map.put(e.getKey(), new int[]{fe.level, fe.fromSlot});
        }
        return map;
    }
}