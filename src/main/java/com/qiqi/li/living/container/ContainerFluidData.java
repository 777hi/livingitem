package com.qiqi.li.living.container;

import java.util.LinkedHashMap;
import java.util.Map;
import com.qiqi.li.living.LivingItemManager;
import net.minecraft.world.item.ItemStack;

/**
 * 容器级流体数据 —— 独立于活物品的槽位级水流状态。
 *
 * 每个容器持有一份实例，生命周期独立于活水桶：
 * - 活水桶放入 → 注册水源槽位
 * - 活水桶 tick → 水源蔓延
 * - 活水桶移除 → 水源取消，但流动槽位继续干涸
 *
 * 和原版流体方块的对应关系：
 * - sourceEntry → 水源方块 (level=0)
 * - flowEntry → 流动水方块 (level=1~7)
 * - 无 entry → 空气
 *
 * 此类将在后续红石系统中复用，作为容器级"信号/状态"的存储基础。
 */
public class ContainerFluidData {

    public static final ContainerFluidData EMPTY = new ContainerFluidData();

    public static final int SOURCE_LEVEL = 0;
    public static final int MAX_FLOW_LEVEL = 7;
    public static final int FLOW_STEP_TICKS = 4;

    public static class FlowEntry {
        int level;
        int timer;
        boolean isSource;
        boolean removed;

        public FlowEntry(int level, int timer, boolean isSource) {
            this.level = level;
            this.timer = timer;
            this.isSource = isSource;
        }

        public int level() { return level; }
        public int timer() { return timer; }
        public boolean isSource() { return isSource; }

        void markRemoved() { this.removed = true; }
    }

    private final Map<Integer, FlowEntry> flows = new LinkedHashMap<>();
    private long lastTickTime;

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
            if (existing.isSource) return;
            existing.isSource = true;
            existing.level = SOURCE_LEVEL;
            existing.timer = 0;
        } else {
            flows.put(slot, new FlowEntry(SOURCE_LEVEL, 0, true));
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

        Map<Integer, FlowEntry> pending = new LinkedHashMap<>();

        for (Map.Entry<Integer, FlowEntry> entry : flows.entrySet()) {
            int slot = entry.getKey();
            FlowEntry fe = entry.getValue();

            if (fe.isSource) {
                tickSource(ctx, slot, containerSize, width, flows, pending);
            } else {
                tickFlow(ctx, slot, fe, containerSize, width, flows, pending);
            }
        }

        flows.values().removeIf(v -> v.removed);
        flows.putAll(pending);
    }

    private void tickSource(ContainerContext ctx, int slot,
                            int containerSize, int width,
                            Map<Integer, FlowEntry> current, Map<Integer, FlowEntry> pending) {
        int[] neighbors = getNeighbors(slot, containerSize, width);
        for (int neighbor : neighbors) {
            if (current.containsKey(neighbor) || pending.containsKey(neighbor)) continue;

            ItemStack item = ctx.getItem(neighbor);
            if (item.isEmpty()) {
                pending.put(neighbor, new FlowEntry(1, FLOW_STEP_TICKS, false));
            } else if (LivingItemManager.isLivingItem(item)) {
            } else {
                int pushTarget = pushItem(ctx, slot, neighbor, containerSize, width);
                if (pushTarget >= 0) {
                    pending.put(neighbor, new FlowEntry(1, FLOW_STEP_TICKS, false));
                }
            }
        }
    }

    private void tickFlow(ContainerContext ctx, int slot, FlowEntry fe,
                          int containerSize, int width,
                          Map<Integer, FlowEntry> current, Map<Integer, FlowEntry> pending) {
        ItemStack item = ctx.getItem(slot);
        if (LivingItemManager.isLivingItem(item)) {
            fe.markRemoved();
            return;
        }

        if (fe.timer > 0) {
            fe.timer--;
            return;
        }

        boolean hasLower = hasLowerLevelNeighbor(slot, fe.level, current, containerSize, width);

        if (!hasLower) {
            if (fe.level >= MAX_FLOW_LEVEL) {
                fe.markRemoved();
                return;
            }
            fe.level++;
            fe.timer = FLOW_STEP_TICKS;
            return;
        }

        if (fe.level >= MAX_FLOW_LEVEL) {
            fe.timer = FLOW_STEP_TICKS;
            return;
        }

        fe.timer = FLOW_STEP_TICKS;

        int[] neighbors = getNeighbors(slot, containerSize, width);
        for (int neighbor : neighbors) {
            if (current.containsKey(neighbor) || pending.containsKey(neighbor)) continue;

            ItemStack neighborItem = ctx.getItem(neighbor);
            if (neighborItem.isEmpty()) {
                pending.put(neighbor, new FlowEntry(fe.level + 1, FLOW_STEP_TICKS, false));
            } else if (LivingItemManager.isLivingItem(neighborItem)) {
            } else {
                int pushTarget = pushItem(ctx, slot, neighbor, containerSize, width);
                if (pushTarget >= 0) {
                    pending.put(neighbor, new FlowEntry(fe.level + 1, FLOW_STEP_TICKS, false));
                }
            }
        }
    }

    private boolean hasLowerLevelNeighbor(int slot, int level,
                                          Map<Integer, FlowEntry> current,
                                          int containerSize, int width) {
        int[] neighbors = getNeighbors(slot, containerSize, width);
        for (int neighbor : neighbors) {
            FlowEntry ne = current.get(neighbor);
            if (ne != null && ne.level < level) {
                return true;
            }
        }
        return false;
    }

    private int[] getNeighbors(int slot, int containerSize, int width) {
        int count = 0;
        boolean left = slot % width > 0;
        boolean right = (slot + 1) % width != 0;
        boolean up = slot >= width;
        boolean down = slot + width < containerSize;

        if (left) count++;
        if (right) count++;
        if (up) count++;
        if (down) count++;

        int[] result = new int[count];
        int i = 0;
        if (left) result[i++] = slot - 1;
        if (right) result[i++] = slot + 1;
        if (up) result[i++] = slot - width;
        if (down) result[i++] = slot + width;
        return result;
    }

    private int pushItem(ContainerContext ctx, int waterSlot, int itemSlot,
                         int containerSize, int width) {
        int dx = (itemSlot % width) - (waterSlot % width);
        int dy = (itemSlot / width) - (waterSlot / width);
        int step = dy * width + dx;

        int primaryTarget = itemSlot + step;
        if (primaryTarget >= 0 && primaryTarget < containerSize
            && !(step == -1 && primaryTarget % width >= itemSlot % width)
            && !(step == 1 && primaryTarget % width <= itemSlot % width)) {
            ItemStack targetItem = ctx.getItem(primaryTarget);
            if (targetItem.isEmpty()) {
                ctx.setItem(primaryTarget, ctx.getItem(itemSlot).copy());
                ctx.setItem(itemSlot, ItemStack.EMPTY);
                return primaryTarget;
            }
        }

        int itemCol = itemSlot % width;
        int itemRow = itemSlot / width;
        int bestSlot = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int s = 0; s < containerSize; s++) {
            if (s == itemSlot) continue;
            if (!ctx.getItem(s).isEmpty()) continue;
            int sCol = s % width;
            int sRow = s / width;
            int dist = Math.abs(sCol - itemCol) + Math.abs(sRow - itemRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestSlot = s;
            }
        }
        if (bestSlot >= 0) {
            ctx.setItem(bestSlot, ctx.getItem(itemSlot).copy());
            ctx.setItem(itemSlot, ItemStack.EMPTY);
            return bestSlot;
        }
        return -1;
    }

    public Map<Integer, Integer> exportFlowData() {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (Map.Entry<Integer, FlowEntry> e : flows.entrySet()) {
            map.put(e.getKey(), e.getValue().level);
        }
        return map;
    }
}