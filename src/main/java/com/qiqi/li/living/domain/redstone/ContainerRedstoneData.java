package com.qiqi.li.living.domain.redstone;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

public class ContainerRedstoneData {

    private static final int PROPAGATION_INTERVAL = 2;

    private static final int E_UP = 0;
    private static final int E_DOWN = 1;
    private static final int E_LEFT = 2;
    private static final int E_RIGHT = 3;

    private final int slotCount;
    private EdgeGrid edgeGrid;
    private EdgeGrid prevEdgeGrid;

    private int tickCounter;
    private boolean processedThisTick;
    private long lastTickTime;

    public ContainerRedstoneData(int size) {
        this.slotCount = size;
        this.tickCounter = 1;
        this.processedThisTick = false;
        this.lastTickTime = System.currentTimeMillis();
    }

    public long getLastTickTime() {
        return lastTickTime;
    }

    public void resetProcessedFlag() {
        this.processedThisTick = false;
    }

    public static int getSignalCap(int stackCount) {
        return stackCount == 1 ? 15 : stackCount * stackCount;
    }

    public int getSignal(int slot) {
        if (edgeGrid == null) return 0;
        return edgeGrid.maxOfSlot(slot);
    }

    public int getTickCounter() {
        return tickCounter;
    }

    public int getSize() {
        return slotCount;
    }

    private void reset() {
        EdgeGrid temp = prevEdgeGrid;
        prevEdgeGrid = edgeGrid;
        edgeGrid = temp;
        if (edgeGrid != null) {
            edgeGrid.zero();
        }
    }

    public void calculate(ContainerContext context, TickContext tick) {
        if (processedThisTick) return;
        processedThisTick = true;
        lastTickTime = System.currentTimeMillis();
        tickCounter++;
        if (tickCounter % PROPAGATION_INTERVAL != 0) return;

        int size = context.getSize();
        int width = context.getWidth();
        int height = (size + width - 1) / width;

        Set<Integer> torchSlots = tick.getFunctionSlots(LivingRedstoneTorchFunction.ID);
        Set<Integer> dustSlots = tick.getFunctionSlots(LivingRedstoneFunction.ID);
        Set<Integer> buttonSlots = tick.getFunctionSlots(LivingButtonFunction.ID);
        Set<Integer> leverSlots = tick.getFunctionSlots(LivingLeverFunction.ID);
        Set<Integer> lampSlots = tick.getFunctionSlots(LivingRedstoneLampFunction.ID);
        Set<Integer> repeaterSlots = tick.getFunctionSlots(LivingRepeaterFunction.ID);
        Set<Integer> comparatorSlots = tick.getFunctionSlots(LivingComparatorFunction.ID);
        Set<Integer> redstoneBlockSlots = tick.getFunctionSlots(LivingRedstoneBlockFunction.ID);

        boolean hasAny = !torchSlots.isEmpty() || !dustSlots.isEmpty()
            || !buttonSlots.isEmpty() || !leverSlots.isEmpty() || !lampSlots.isEmpty()
            || !repeaterSlots.isEmpty() || !comparatorSlots.isEmpty()
            || !redstoneBlockSlots.isEmpty();
        if (!hasAny) return;

        if (edgeGrid == null || edgeGrid.width != width || edgeGrid.height != height) {
            edgeGrid = new EdgeGrid(width, height);
            prevEdgeGrid = new EdgeGrid(width, height);
        }
        reset();

        phase0CountdownDelays(repeaterSlots, buttonSlots, size, context);
        Queue<Integer> queue = phase1CollectSources(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, size, width, context);
        phase2Propagation(queue, dustSlots, repeaterSlots, comparatorSlots,
            torchSlots, lampSlots, size, width, context);
        phase3RecheckInputs(repeaterSlots, comparatorSlots, size, context);
        phase4UpdateDisplay(torchSlots, dustSlots, lampSlots, size, context);
    }

    private void phase0CountdownDelays(Set<Integer> repeaterSlots, Set<Integer> buttonSlots,
            int size, ContainerContext context) {
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (!data.powered()) continue;
            if (data.delayTimer() == 0) continue;

            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = prevEdgeGrid.get(slot, inputDir) > 0;

            if (data.delayTimer() > 0) {
                if (!hasInput) {
                    data = data.withPowered(false).withDelayTimer(0);
                } else {
                    data = data.withDelayTimer(data.delayTimer() - 1);
                }
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            } else {
                int newTimer = data.delayTimer() + 1;
                if (newTimer == 0) {
                    data = data.withPowered(false).withDelayTimer(0);
                } else {
                    data = data.withDelayTimer(newTimer);
                }
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (data.pressed() && data.pulseTimer() > 0) {
                int newTimer = data.pulseTimer() - 2;
                if (newTimer <= 0) {
                    LivingItemManager.setButtonData(stack, data.withPressed(false).withPulseTimer(0));
                    context.syncSlotToClients(slot, stack);
                } else {
                    LivingItemManager.setButtonData(stack, data.withPulseTimer(newTimer));
                    context.syncSlotToClients(slot, stack);
                }
            }
        }
    }

    private Queue<Integer> phase1CollectSources(Set<Integer> torchSlots, Set<Integer> buttonSlots,
            Set<Integer> leverSlots, Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> dustSlots, Set<Integer> redstoneBlockSlots, int size, int width, ContainerContext context) {
        Queue<Integer> queue = new ArrayDeque<>();

        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            if (!data.isLit()) continue;

            int cap = getSignalCap(stack.getCount());
            int skipDir = edgeIndex(data.direction().opposite());

            for (int dir = 0; dir < 4; dir++) {
                if (dir == skipDir) continue;
                int neighbor = resolveSlot(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (!data.pressed()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = resolveSlot(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : leverSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingLeverData data = LivingItemManager.getLeverData(stack);
            if (!data.powered()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = resolveSlot(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (!data.powered() || data.delayTimer() > 0) continue;

            int cap = getSignalCap(stack.getCount());
            int outDir = edgeIndex(data.direction());
            int neighbor = resolveSlot(slot, outDir, size, width);
            if (cap > edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, cap);
                if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data);
            if (output <= 0) continue;

            int outDir = edgeIndex(data.direction());
            int neighbor = resolveSlot(slot, outDir, size, width);
            if (output > edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, output);
                if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        for (int slot : redstoneBlockSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = resolveSlot(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        return queue;
    }

    private void phase2Propagation(Queue<Integer> queue, Set<Integer> dustSlots,
            Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> torchSlots, Set<Integer> lampSlots,
            int size, int width, ContainerContext context) {
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (!dustSlots.contains(current)) continue;

            ItemStack stack = context.getItem(current);
            if (stack.isEmpty()) continue;

            int maxInput = edgeGrid.maxOfSlot(current);
            if (maxInput <= 1) continue;

            int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));

            for (int dir = 0; dir < 4; dir++) {
                int neighbor = resolveSlot(current, dir, size, width);
                boolean isTarget = neighbor >= 0 && isRedstoneTarget(neighbor, dustSlots,
                    repeaterSlots, comparatorSlots, torchSlots, lampSlots);
                if (!isTarget && neighbor >= 0) continue;

                if (output > edgeGrid.get(current, dir)) {
                    edgeGrid.set(current, dir, output);
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
    }

    private void phase3RecheckInputs(Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            int size, ContainerContext context) {
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (data.powered() && data.delayTimer() > 0) continue;

            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = edgeGrid.get(slot, inputDir) > 0;

            if (hasInput && !data.powered()) {
                LivingItemManager.setRepeaterData(stack, data.withPowered(true).withDelayTimer(data.delay()));
                context.syncSlotToClients(slot, stack);
            } else if (hasInput && data.powered() && data.delayTimer() < 0) {
                LivingItemManager.setRepeaterData(stack, data.withDelayTimer(0));
                context.syncSlotToClients(slot, stack);
            } else if (!hasInput && data.powered() && data.delayTimer() == 0) {
                LivingItemManager.setRepeaterData(stack, data.withDelayTimer(-data.delay()));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data);
            boolean newPowered = output > 0;
            if (data.powered() != newPowered) {
                LivingItemManager.setComparatorData(stack, data.withPowered(newPowered));
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    private void phase4UpdateDisplay(Set<Integer> torchSlots, Set<Integer> dustSlots,
            Set<Integer> lampSlots, int size, ContainerContext context) {
        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = edgeGrid.get(slot, inputDir) > 0;
            boolean newLit = !hasInput;
            if (data.isLit() != newLit) {
                LivingItemManager.setRedstoneTorchData(stack, data.withLit(newLit));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : dustSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int maxSignal = edgeGrid.maxOfSlot(slot);
            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            if (data.signalStrength() != maxSignal || data.isPowered() != (maxSignal > 0)) {
                LivingItemManager.setRedstoneData(stack, data.withSignal(maxSignal).withPowered(maxSignal > 0));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : lampSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            boolean hasSignal = edgeGrid.anyOfSlot(slot);
            LivingRedstoneLampData data = LivingItemManager.getLampData(stack);
            if (data.lit() != hasSignal) {
                LivingItemManager.setLampData(stack, data.withLit(hasSignal));
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    private boolean isRedstoneTarget(int slot, Set<Integer> dustSlots, Set<Integer> repeaterSlots,
            Set<Integer> comparatorSlots, Set<Integer> torchSlots, Set<Integer> lampSlots) {
        return dustSlots.contains(slot) || repeaterSlots.contains(slot)
            || comparatorSlots.contains(slot) || torchSlots.contains(slot) || lampSlots.contains(slot);
    }

    private int computeComparatorOutput(int slot, LivingComparatorData data) {
        int inputDir = edgeIndex(data.direction().opposite());
        int signalA = edgeGrid.get(slot, inputDir);

        int[] sideDirs = perpendicularEdges(inputDir);
        int signalB = 0;
        for (int sideDir : sideDirs) {
            signalB = Math.max(signalB, edgeGrid.get(slot, sideDir));
        }

        int output;
        if (data.subtractMode()) {
            output = Math.max(0, signalA - signalB);
        } else {
            output = signalA >= signalB ? signalA : 0;
        }

        return output;
    }

    private static int[] perpendicularEdges(int dir) {
        if (dir == E_UP || dir == E_DOWN) {
            return new int[] {E_LEFT, E_RIGHT};
        } else {
            return new int[] {E_UP, E_DOWN};
        }
    }

    private static int resolveSlot(int slot, int dir, int size, int width) {
        int col = slot % width;
        int row = slot / width;
        int newCol = col;
        int newRow = row;
        switch (dir) {
            case E_UP:    newRow = row - 1; break;
            case E_DOWN:  newRow = row + 1; break;
            case E_LEFT:  newCol = col - 1; break;
            case E_RIGHT: newCol = col + 1; break;
            default: return -1;
        }
        if (newCol < 0 || newCol >= width || newRow < 0) return -1;
        int result = newRow * width + newCol;
        return result < size ? result : -1;
    }

    private static int edgeIndex(Pos2D dir) {
        if (dir.x() == 0) {
            return dir.y() < 0 ? E_UP : E_DOWN;
        } else {
            return dir.x() < 0 ? E_LEFT : E_RIGHT;
        }
    }

    /**
     * 共享边网格：相邻槽位之间的边只有一条，两个槽位读写同一数组条目。
     * 边界边也存储，为跨容器信号传输预留。
     *
     * 对于 W×H 的槽位网格，每行有 W+1 条水平边，每列有 H+1 条垂直边：
     *   hEdges[height * (width + 1)] — 水平边（含左右边界）
     *   vEdges[(height + 1) * width] — 垂直边（含上下边界）
     *
     * 槽位 (r,c) 的边：
     *   LEFT  → hEdges[r * (W+1) + c]
     *   RIGHT → hEdges[r * (W+1) + (c+1)]
     *   UP    → vEdges[r * W + c]
     *   DOWN  → vEdges[(r+1) * W + c]
     */
    private static class EdgeGrid {
        final int width;
        final int height;
        final int[] hEdges;
        final int[] vEdges;

        EdgeGrid(int width, int height) {
            this.width = width;
            this.height = height;
            this.hEdges = new int[height * (width + 1)];
            this.vEdges = new int[(height + 1) * width];
        }

        int get(int slot, int dir) {
            int r = slot / width;
            int c = slot % width;
            switch (dir) {
                case E_UP:    return vEdges[r * width + c];
                case E_DOWN:  return vEdges[(r + 1) * width + c];
                case E_LEFT:  return hEdges[r * (width + 1) + c];
                case E_RIGHT: return hEdges[r * (width + 1) + (c + 1)];
                default: return 0;
            }
        }

        void set(int slot, int dir, int value) {
            int r = slot / width;
            int c = slot % width;
            switch (dir) {
                case E_UP:    vEdges[r * width + c] = value; break;
                case E_DOWN:  vEdges[(r + 1) * width + c] = value; break;
                case E_LEFT:  hEdges[r * (width + 1) + c] = value; break;
                case E_RIGHT: hEdges[r * (width + 1) + (c + 1)] = value; break;
            }
        }

        int maxOfSlot(int slot) {
            int max = 0;
            for (int dir = 0; dir < 4; dir++) {
                max = Math.max(max, get(slot, dir));
            }
            return max;
        }

        boolean anyOfSlot(int slot) {
            for (int dir = 0; dir < 4; dir++) {
                if (get(slot, dir) > 0) return true;
            }
            return false;
        }

        void zero() {
            Arrays.fill(hEdges, 0);
            Arrays.fill(vEdges, 0);
        }
    }
}