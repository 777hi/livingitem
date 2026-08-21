package com.qiqi.li.living.domain.redstone;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.hopper.CrossContainerTransfer;
import com.qiqi.li.living.model.Pos2D;

public class ContainerRedstoneData {

    private static final int PROPAGATION_INTERVAL = 2;

    private static final int E_UP = 0;
    private static final int E_DOWN = 1;
    private static final int E_LEFT = 2;
    private static final int E_RIGHT = 3;

    private final int slotCount;
    private int lastWidth;
    private int lastHeight;
    private EdgeGrid edgeGrid;
    private EdgeGrid prevEdgeGrid;
    private final int[] externalInputs = new int[4];
    private final int[] prevBoundaryOutput = new int[4];
    private final int[] boundaryOutput = new int[4];

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
        java.util.Arrays.fill(externalInputs, 0);
        java.util.Arrays.fill(boundaryOutput, 0);
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
        this.lastWidth = width;
        this.lastHeight = height;

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
        if (edgeGrid == null || edgeGrid.width != width || edgeGrid.height != height) {
            edgeGrid = new EdgeGrid(width, height);
            prevEdgeGrid = new EdgeGrid(width, height);
        }
        reset();

        injectExternalInputs(context);

        if (!hasAny) {
            notifyBoundaryChange(context, width, height);
            return;
        }

        phase0CountdownDelays(repeaterSlots, buttonSlots, size, context);
        Queue<Integer> queue = phase1CollectSources(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, size, width, context);
        seedBoundaryDust(queue, dustSlots, width, height);
        phase2Propagation(queue, dustSlots, repeaterSlots, comparatorSlots,
            torchSlots, lampSlots, size, width, context);
        phase4PowerConductors(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots,
            size, width, context);
        phase3RecheckInputs(repeaterSlots, comparatorSlots, size, width, context);
        phase5UpdateDisplay(torchSlots, dustSlots, lampSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, redstoneBlockSlots, size, width, context);
        notifyBoundaryChange(context, width, height);
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
            int output = computeComparatorOutput(slot, data, context, size, width);
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
        int height = (size + width - 1) / width;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (!dustSlots.contains(current)) continue;

            ItemStack stack = context.getItem(current);
            if (stack.isEmpty()) continue;

            int maxInput = edgeGrid.maxOfSlot(current);
            if (maxInput <= 1) continue;

            int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));

            int r = current / width;
            int c = current % width;

            for (int dir = 0; dir < 4; dir++) {
                int neighbor = resolveSlot(current, dir, size, width);
                boolean isTarget = neighbor >= 0 && isRedstoneTarget(neighbor, dustSlots,
                    repeaterSlots, comparatorSlots, torchSlots, lampSlots);
                if (!isTarget && neighbor >= 0) continue;

                boolean isBoundary = (dir == E_UP && r == 0) || (dir == E_DOWN && r == height - 1)
                    || (dir == E_LEFT && c == 0) || (dir == E_RIGHT && c == width - 1);

                if (isBoundary) {
                    int internalMax = 0;
                    for (int d = 0; d < 4; d++) {
                        if (d == dir) continue;
                        internalMax = Math.max(internalMax, edgeGrid.get(current, d));
                    }
                    int internalOutput = Math.min(internalMax - 1, getSignalCap(stack.getCount()));
                    if (internalOutput > 0) {
                        boundaryOutput[dir] = Math.max(boundaryOutput[dir], internalOutput);
                    }
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                } else {
                    int currentEdge = edgeGrid.get(current, dir);
                    if (output <= currentEdge) continue;
                    edgeGrid.set(current, dir, output);
                    if (neighbor >= 0 && dustSlots.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
    }

    private void phase3RecheckInputs(Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            int size, int width, ContainerContext context) {
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
            int output = computeComparatorOutput(slot, data, context, size, width);
            boolean newPowered = output > 0;
            if (data.powered() != newPowered) {
                LivingItemManager.setComparatorData(stack, data.withPowered(newPowered));
                context.syncSlotToClients(slot, stack);
            }

            int outDir = edgeIndex(data.direction());
            if (output != edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, output);
            }
        }
    }

    private void phase4PowerConductors(Set<Integer> torchSlots, Set<Integer> buttonSlots,
            Set<Integer> leverSlots, Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> dustSlots, Set<Integer> redstoneBlockSlots,
            int size, int width, ContainerContext context) {
        Set<Integer> allRedstone = new HashSet<>();
        allRedstone.addAll(torchSlots);
        allRedstone.addAll(buttonSlots);
        allRedstone.addAll(leverSlots);
        allRedstone.addAll(repeaterSlots);
        allRedstone.addAll(comparatorSlots);
        allRedstone.addAll(dustSlots);
        allRedstone.addAll(redstoneBlockSlots);

        Queue<Integer> secondQueue = new ArrayDeque<>();

        for (int slot : dustSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            byte conn = data.connections();
            int maxInput = edgeGrid.maxOfSlot(slot);
            if (maxInput <= 1) continue;

            int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));
            for (int dir = 0; dir < 4; dir++) {
                if ((conn & (1 << dir)) == 0) continue;
                int neighbor = resolveSlot(slot, dir, size, width);
                if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
                if (!isConductiveBlock(context.getItem(neighbor))) continue;

                for (int d2 = 0; d2 < 4; d2++) {
                    if (output > edgeGrid.get(neighbor, d2)) {
                        edgeGrid.set(neighbor, d2, output);
                        int n2 = resolveSlot(neighbor, d2, size, width);
                        if (n2 >= 0 && dustSlots.contains(n2)) {
                            secondQueue.add(n2);
                        }
                    }
                }
            }
        }

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
                if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
                if (!isConductiveBlock(context.getItem(neighbor))) continue;

                for (int d2 = 0; d2 < 4; d2++) {
                    if (cap > edgeGrid.get(neighbor, d2)) {
                        edgeGrid.set(neighbor, d2, cap);
                        int n2 = resolveSlot(neighbor, d2, size, width);
                        if (n2 >= 0 && dustSlots.contains(n2)) {
                            secondQueue.add(n2);
                        }
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
                if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
                if (!isConductiveBlock(context.getItem(neighbor))) continue;

                for (int d2 = 0; d2 < 4; d2++) {
                    if (cap > edgeGrid.get(neighbor, d2)) {
                        edgeGrid.set(neighbor, d2, cap);
                        int n2 = resolveSlot(neighbor, d2, size, width);
                        if (n2 >= 0 && dustSlots.contains(n2)) {
                            secondQueue.add(n2);
                        }
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
                if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
                if (!isConductiveBlock(context.getItem(neighbor))) continue;

                for (int d2 = 0; d2 < 4; d2++) {
                    if (cap > edgeGrid.get(neighbor, d2)) {
                        edgeGrid.set(neighbor, d2, cap);
                        int n2 = resolveSlot(neighbor, d2, size, width);
                        if (n2 >= 0 && dustSlots.contains(n2)) {
                            secondQueue.add(n2);
                        }
                    }
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
                if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
                if (!isConductiveBlock(context.getItem(neighbor))) continue;

                for (int d2 = 0; d2 < 4; d2++) {
                    if (cap > edgeGrid.get(neighbor, d2)) {
                        edgeGrid.set(neighbor, d2, cap);
                        int n2 = resolveSlot(neighbor, d2, size, width);
                        if (n2 >= 0 && dustSlots.contains(n2)) {
                            secondQueue.add(n2);
                        }
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
            if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
            if (!isConductiveBlock(context.getItem(neighbor))) continue;

            for (int d2 = 0; d2 < 4; d2++) {
                if (cap > edgeGrid.get(neighbor, d2)) {
                    edgeGrid.set(neighbor, d2, cap);
                    int n2 = resolveSlot(neighbor, d2, size, width);
                    if (n2 >= 0 && dustSlots.contains(n2)) {
                        secondQueue.add(n2);
                    }
                }
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, context, size, width);
            if (output <= 0) continue;

            int outDir = edgeIndex(data.direction());
            int neighbor = resolveSlot(slot, outDir, size, width);
            if (neighbor < 0 || allRedstone.contains(neighbor)) continue;
            if (!isConductiveBlock(context.getItem(neighbor))) continue;

            for (int d2 = 0; d2 < 4; d2++) {
                if (output > edgeGrid.get(neighbor, d2)) {
                    edgeGrid.set(neighbor, d2, output);
                    int n2 = resolveSlot(neighbor, d2, size, width);
                    if (n2 >= 0 && dustSlots.contains(n2)) {
                        secondQueue.add(n2);
                    }
                }
            }
        }

        if (!secondQueue.isEmpty()) {
            phase2Propagation(secondQueue, dustSlots, repeaterSlots, comparatorSlots,
                torchSlots, new HashSet<>(), size, width, context);
        }
    }

    private void injectExternalInputs(ContainerContext context) {
        BlockPos pos = context.getBlockPos();
        Level level = context.getLevel();
        if (pos == null || level == null) return;

        BlockState state = level.getBlockState(pos);
        if (state == null) return;

        Direction facing = CrossContainerTransfer.getBlockFacing(state);
        if (facing == null) return;

        for (Direction worldDir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(worldDir);
            int signal = level.getSignal(neighborPos, worldDir);
            if (signal > 0) {
                Pos2D gridDir = CrossContainerTransfer.worldToGrid(worldDir, facing);
                if (gridDir != null && !gridDir.isNone()) {
                    int internalDir = edgeIndex(gridDir);
                    externalInputs[internalDir] = Math.max(externalInputs[internalDir], signal);
                    injectBoundarySignal(internalDir, signal);
                }
            }
        }
    }

    private void seedBoundaryDust(Queue<Integer> queue, Set<Integer> dustSlots, int width, int height) {
        if (edgeGrid == null) return;
        for (int c = 0; c < width; c++) {
            if (edgeGrid.vEdges[c] > 0) {
                int slot = c;
                if (dustSlots.contains(slot)) queue.add(slot);
            }
            int bottomIdx = height * width + c;
            if (edgeGrid.vEdges[bottomIdx] > 0) {
                int slot = (height - 1) * width + c;
                if (dustSlots.contains(slot)) queue.add(slot);
            }
        }
        for (int r = 0; r < height; r++) {
            if (edgeGrid.hEdges[r * (width + 1)] > 0) {
                int slot = r * width;
                if (dustSlots.contains(slot)) queue.add(slot);
            }
            if (edgeGrid.hEdges[r * (width + 1) + width] > 0) {
                int slot = r * width + (width - 1);
                if (dustSlots.contains(slot)) queue.add(slot);
            }
        }
    }

    private void notifyBoundaryChange(ContainerContext context, int width, int height) {
        if (edgeGrid == null || prevEdgeGrid == null) return;
        BlockPos pos = context.getBlockPos();
        Level level = context.getLevel();
        if (pos == null || level == null || level.isClientSide) return;

        boolean changed = false;
        for (int dir = 0; dir < 4 && !changed; dir++) {
            int current = getBoundarySignal(dir);
            if (current != prevBoundaryOutput[dir]) changed = true;
        }
        for (int dir = 0; dir < 4; dir++) {
            prevBoundaryOutput[dir] = getBoundarySignal(dir);
        }
        if (changed) {
            BlockState state = level.getBlockState(pos);
            if (state != null) {
                level.updateNeighborsAt(pos, state.getBlock());
            }
        }
    }

    public int getBoundarySignal(int internalDir) {
        return boundaryOutput[internalDir];
    }

    void injectBoundarySignal(int internalDir, int signal) {
        if (edgeGrid == null) return;
        int height = lastHeight;
        if (height <= 0) return;
        switch (internalDir) {
            case E_UP:
                for (int c = 0; c < edgeGrid.width; c++) {
                    if (signal > edgeGrid.vEdges[c]) {
                        edgeGrid.vEdges[c] = signal;
                    }
                }
                break;
            case E_DOWN:
                for (int c = 0; c < edgeGrid.width; c++) {
                    if (signal > edgeGrid.vEdges[height * edgeGrid.width + c]) {
                        edgeGrid.vEdges[height * edgeGrid.width + c] = signal;
                    }
                }
                break;
            case E_LEFT:
                for (int r = 0; r < height; r++) {
                    if (signal > edgeGrid.hEdges[r * (edgeGrid.width + 1)]) {
                        edgeGrid.hEdges[r * (edgeGrid.width + 1)] = signal;
                    }
                }
                break;
            case E_RIGHT:
                for (int r = 0; r < height; r++) {
                    if (signal > edgeGrid.hEdges[r * (edgeGrid.width + 1) + edgeGrid.width]) {
                        edgeGrid.hEdges[r * (edgeGrid.width + 1) + edgeGrid.width] = signal;
                    }
                }
                break;
        }
    }

    private static boolean isConductiveBlock(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            return LivingItemManager.isLivingItem(stack)
                && blockItem.getBlock().defaultBlockState()
                    .isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        }
        return false;
    }

    private void phase5UpdateDisplay(Set<Integer> torchSlots, Set<Integer> dustSlots,
            Set<Integer> lampSlots, Set<Integer> buttonSlots, Set<Integer> leverSlots,
            Set<Integer> repeaterSlots, Set<Integer> comparatorSlots, Set<Integer> redstoneBlockSlots,
            int size, int width, ContainerContext context) {
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
            byte conn = computeDustConnections(slot, size, width, context,
                buttonSlots, leverSlots, repeaterSlots, comparatorSlots,
                torchSlots, dustSlots, lampSlots, redstoneBlockSlots);
            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            if (data.signalStrength() != maxSignal || data.isPowered() != (maxSignal > 0)
                || data.connections() != conn) {
                LivingItemManager.setRedstoneData(stack,
                    data.withSignal(maxSignal).withPowered(maxSignal > 0).withConnections(conn));
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

    private static final Pos2D[] DIR_POS = {Pos2D.UP, Pos2D.DOWN, Pos2D.LEFT, Pos2D.RIGHT};

    private byte computeDustConnections(int slot, int size, int width, ContainerContext context,
            Set<Integer> buttonSlots, Set<Integer> leverSlots, Set<Integer> repeaterSlots,
            Set<Integer> comparatorSlots, Set<Integer> torchSlots, Set<Integer> dustSlots,
            Set<Integer> lampSlots, Set<Integer> redstoneBlockSlots) {
        byte conn = 0;
        for (int dir = 0; dir < 4; dir++) {
            int neighbor = resolveSlot(slot, dir, size, width);
            if (neighbor < 0) continue;

            if (repeaterSlots.contains(neighbor) || comparatorSlots.contains(neighbor)) {
                ItemStack ns = context.getItem(neighbor);
                if (!ns.isEmpty()) {
                    Pos2D facing = repeaterSlots.contains(neighbor)
                        ? LivingItemManager.getRepeaterData(ns).direction()
                        : LivingItemManager.getComparatorData(ns).direction();
                    Pos2D d = DIR_POS[dir];
                    if (d.equals(facing) || d.equals(facing.opposite())) {
                        conn |= (1 << dir);
                    }
                }
            } else if (buttonSlots.contains(neighbor) || leverSlots.contains(neighbor)
                || torchSlots.contains(neighbor) || dustSlots.contains(neighbor)
                || lampSlots.contains(neighbor) || redstoneBlockSlots.contains(neighbor)) {
                conn |= (1 << dir);
            }
        }

        if (conn != 0) {
            boolean hasUp = (conn & 1) != 0;
            boolean hasDown = (conn & 2) != 0;
            boolean hasLeft = (conn & 4) != 0;
            boolean hasRight = (conn & 8) != 0;

            boolean noVertical = !hasUp && !hasDown;
            boolean noHorizontal = !hasLeft && !hasRight;

            if (!hasLeft && noVertical) conn |= 4;
            if (!hasRight && noVertical) conn |= 8;
            if (!hasUp && noHorizontal) conn |= 1;
            if (!hasDown && noHorizontal) conn |= 2;
        }

        return conn;
    }

    private boolean isRedstoneTarget(int slot, Set<Integer> dustSlots, Set<Integer> repeaterSlots,
            Set<Integer> comparatorSlots, Set<Integer> torchSlots, Set<Integer> lampSlots) {
        return dustSlots.contains(slot) || repeaterSlots.contains(slot)
            || comparatorSlots.contains(slot) || torchSlots.contains(slot) || lampSlots.contains(slot);
    }

    private int computeComparatorOutput(int slot, LivingComparatorData data,
            ContainerContext context, int size, int width) {
        int inputDir = edgeIndex(data.direction().opposite());
        int signalA = edgeGrid.get(slot, inputDir);

        ItemStack comparatorStack = context.getItem(slot);
        int signalCap = getSignalCap(comparatorStack.getCount());

        if (signalA == 0) {
            int backSlot = resolveSlot(slot, inputDir, size, width);
            if (backSlot >= 0) {
                ItemStack backStack = context.getItem(backSlot);
                signalA = LivingComparatorFunction.readComparatorOutput(backStack, signalCap);
            }
        }

        int[] sideDirs = perpendicularEdges(inputDir);
        int signalB = 0;
        for (int sideDir : sideDirs) {
            int edgeSignal = edgeGrid.get(slot, sideDir);
            if (edgeSignal > 0) {
                signalB = Math.max(signalB, edgeSignal);
            } else {
                int sideSlot = resolveSlot(slot, sideDir, size, width);
                if (sideSlot >= 0) {
                    ItemStack sideStack = context.getItem(sideSlot);
                    int sideOutput = LivingComparatorFunction.readComparatorOutput(sideStack, signalCap);
                    signalB = Math.max(signalB, sideOutput);
                }
            }
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
    private class EdgeGrid {
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
                case E_UP:    vEdges[r * width + c] = value; if (r == 0)            boundaryOutput[E_UP]    = Math.max(boundaryOutput[E_UP],    value); break;
                case E_DOWN:  vEdges[(r + 1) * width + c] = value; if (r == height - 1) boundaryOutput[E_DOWN]  = Math.max(boundaryOutput[E_DOWN],  value); break;
                case E_LEFT:  hEdges[r * (width + 1) + c] = value; if (c == 0)            boundaryOutput[E_LEFT]  = Math.max(boundaryOutput[E_LEFT],  value); break;
                case E_RIGHT: hEdges[r * (width + 1) + (c + 1)] = value; if (c == width - 1) boundaryOutput[E_RIGHT] = Math.max(boundaryOutput[E_RIGHT], value); break;
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