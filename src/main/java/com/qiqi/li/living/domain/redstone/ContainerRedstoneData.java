package com.qiqi.li.living.domain.redstone;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.HashSet;
import java.util.List;
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
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.hopper.CrossContainerTransfer;
import com.qiqi.li.living.model.Pos2D;

public class ContainerRedstoneData {

    private static final int PROPAGATION_INTERVAL = 2;

    private static final int E_UP = 0;
    private static final int E_DOWN = 1;
    private static final int E_LEFT = 2;
    private static final int E_RIGHT = 3;

    private EdgeGrid edgeGrid;
    private EdgeGrid prevEdgeGrid;
    private final int[] faceInput = new int[4];
    private final int[] faceOutput = new int[4];
    private final int[] prevFaceOutput = new int[4];

    private int tickCounter;
    private boolean processedThisTick;
    private long lastTickTime;

    public ContainerRedstoneData() {
        this.tickCounter = 1; // 初始化为奇数，首次调用后递增到偶数，确保首次不跳过传播
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

    public int getSlotSignal(int slot, int size, int width) {
        if (edgeGrid == null) return 0;
        int height = (size + width - 1) / width;

        int maxSignal = edgeGrid.maxOfSlot(slot);

        int r = slot / width;
        int c = slot % width;

        if (r == 0) maxSignal = Math.max(maxSignal, faceInput[E_UP]);
        if (r == height - 1) maxSignal = Math.max(maxSignal, faceInput[E_DOWN]);
        if (c == 0) maxSignal = Math.max(maxSignal, faceInput[E_LEFT]);
        if (c == width - 1) maxSignal = Math.max(maxSignal, faceInput[E_RIGHT]);

        return maxSignal;
    }

    private void reset() {
        EdgeGrid temp = prevEdgeGrid;
        prevEdgeGrid = edgeGrid;
        edgeGrid = temp;
        edgeGrid.zero();
        java.util.Arrays.fill(faceInput, 0);
    }

    public void calculate(ContainerContext context, TickContext tick) {
        if (processedThisTick) return;
        processedThisTick = true;
        lastTickTime = System.currentTimeMillis();
        tickCounter++;

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
        if (edgeGrid == null || edgeGrid.width != width || edgeGrid.height != height) {
            edgeGrid = new EdgeGrid(width, height);
            prevEdgeGrid = new EdgeGrid(width, height);
        }

        if (!hasAny) {
            if (edgeGrid != null) edgeGrid.zero();
            computeFaceOutput(width, height);
            notifyBoundaryChange(context, width, height);
            return;
        }

        if (tickCounter % PROPAGATION_INTERVAL != 0) {
            return;
        }

        reset();
        injectExternalInputs(context);

        phase0CountdownDelays(repeaterSlots, buttonSlots, size, width, context);
        Queue<Integer> queue = phase1CollectSources(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, size, width, context);
        phase2Propagation(queue, dustSlots, repeaterSlots, comparatorSlots,
            torchSlots, lampSlots, size, width, context);
        phase4PowerConductors(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots,
            size, width, context);
        phase3RecheckInputs(repeaterSlots, comparatorSlots, size, width, context);
        phase5UpdateDisplay(torchSlots, dustSlots, lampSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, redstoneBlockSlots, size, width, context);
        computeFaceOutput(width, height);
        notifyBoundaryChange(context, width, height);
    }

    private void phase0CountdownDelays(Set<Integer> repeaterSlots, Set<Integer> buttonSlots,
            int size, int width, ContainerContext context) {
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (data.locked()) continue;
            if (!data.powered()) continue;
            if (data.delayTimer() == 0) continue;

            if (data.delayTimer() > 0) {
                data = data.withDelayTimer(data.delayTimer() - 1);
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

                int currentEdge = edgeGrid.get(current, dir);
                if (output <= currentEdge) continue;
                edgeGrid.set(current, dir, output);

                boolean isTarget = neighbor >= 0 && isRedstoneTarget(neighbor, dustSlots,
                    repeaterSlots, comparatorSlots, torchSlots, lampSlots);
                if (isTarget && dustSlots.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
    }

    private void phase3RecheckInputs(Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            int size, int width, ContainerContext context) {
        int height = (size + width - 1) / width;
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);

            boolean locked = checkRepeaterLocked(slot, data, repeaterSlots, size, width, context);
            if (data.locked() != locked) {
                data = data.withLocked(locked);
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
            if (locked) continue;

            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = getEffectiveInput(slot, inputDir, width, height) > 0;

            if (data.powered() && data.delayTimer() > 0) continue;

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
                powerConductiveNeighbor(slot, output, dir, allRedstone, dustSlots,
                    size, width, context, secondQueue);
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
                powerConductiveNeighbor(slot, cap, dir, allRedstone, dustSlots,
                    size, width, context, secondQueue);
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
                powerConductiveNeighbor(slot, cap, dir, allRedstone, dustSlots,
                    size, width, context, secondQueue);
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
                powerConductiveNeighbor(slot, cap, dir, allRedstone, dustSlots,
                    size, width, context, secondQueue);
            }
        }

        for (int slot : redstoneBlockSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                powerConductiveNeighbor(slot, cap, dir, allRedstone, dustSlots,
                    size, width, context, secondQueue);
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
            powerConductiveNeighbor(slot, cap, outDir, allRedstone, dustSlots,
                size, width, context, secondQueue);
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, context, size, width);
            if (output <= 0) continue;

            int outDir = edgeIndex(data.direction());
            powerConductiveNeighbor(slot, output, outDir, allRedstone, dustSlots,
                size, width, context, secondQueue);
        }

        if (!secondQueue.isEmpty()) {
            phase2Propagation(secondQueue, dustSlots, repeaterSlots, comparatorSlots,
                torchSlots, new HashSet<>(), size, width, context);
        }
    }

    private void powerConductiveNeighbor(int slot, int signal, int dir, Set<Integer> allRedstone,
            Set<Integer> dustSlots, int size, int width, ContainerContext context,
            Queue<Integer> secondQueue) {
        int neighbor = resolveSlot(slot, dir, size, width);
        if (neighbor < 0 || allRedstone.contains(neighbor)) return;
        if (!isConductiveBlock(context.getItem(neighbor))) return;

        for (int d2 = 0; d2 < 4; d2++) {
            if (signal > edgeGrid.get(neighbor, d2)) {
                edgeGrid.set(neighbor, d2, signal);
                int n2 = resolveSlot(neighbor, d2, size, width);
                if (n2 >= 0 && dustSlots.contains(n2)) {
                    secondQueue.add(n2);
                }
            }
        }
    }

    private void injectExternalInputs(ContainerContext context) {
        Level level = context.getLevel();
        if (level == null) return;

        List<BlockPos> positions = context.getAssociatedBlockPositions();
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (state == null) continue;

            Direction facing = CrossContainerTransfer.getBlockFacing(state);
            if (facing == null) continue;

            for (Direction worldDir : Direction.Plane.HORIZONTAL) {
                BlockPos neighborPos = pos.relative(worldDir);
                int signal = level.getSignal(neighborPos, worldDir);

                ContainerRedstoneData neighborData = ContainerLivingItemHandler.getRedstoneDataByPos(level, neighborPos);
                if (neighborData != null) {
                    BlockState neighborState = level.getBlockState(neighborPos);
                    Direction neighborFacing = CrossContainerTransfer.getBlockFacing(neighborState);
                    if (neighborFacing != null) {
                        Pos2D neighborGridDir = CrossContainerTransfer.worldToGrid(worldDir.getOpposite(), neighborFacing);
                        if (neighborGridDir != null && !neighborGridDir.isNone()) {
                            int neighborInternalDir = edgeIndex(neighborGridDir);
                            signal = Math.max(signal, neighborData.getBoundarySignal(neighborInternalDir));
                        }
                    }
                }

                if (signal > 0) {
                    Pos2D gridDir = CrossContainerTransfer.worldToGrid(worldDir, facing);
                    if (gridDir != null && !gridDir.isNone()) {
                        int internalDir = edgeIndex(gridDir);
                        faceInput[internalDir] = Math.max(faceInput[internalDir], signal);
                    }
                }
            }
        }
    }

    private boolean checkRepeaterLocked(int slot, LivingRepeaterData data, Set<Integer> repeaterSlots,
            int size, int width, ContainerContext context) {
        Pos2D dir = data.direction();
        boolean isVertical = dir.equals(Pos2D.UP) || dir.equals(Pos2D.DOWN);
        int[] perpDirs = isVertical ? new int[]{E_LEFT, E_RIGHT} : new int[]{E_UP, E_DOWN};

        for (int perpDir : perpDirs) {
            int neighbor = resolveSlot(slot, perpDir, size, width);
            if (neighbor >= 0 && repeaterSlots.contains(neighbor)) {
                ItemStack neighborStack = context.getItem(neighbor);
                if (!neighborStack.isEmpty()) {
                    LivingRepeaterData neighborData = LivingItemManager.getRepeaterData(neighborStack);
                    if (neighborData.powered() && neighborData.delayTimer() == 0
                            && neighborData.direction().equals(requiredDir(perpDir))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void notifyBoundaryChange(ContainerContext context, int width, int height) {
        Level level = context.getLevel();
        if (level == null || level.isClientSide) return;

        boolean changed = !Arrays.equals(faceOutput, prevFaceOutput);
        System.arraycopy(faceOutput, 0, prevFaceOutput, 0, 4);
        if (changed) {
            for (BlockPos pos : context.getAssociatedBlockPositions()) {
                BlockState state = level.getBlockState(pos);
                if (state != null) {
                    level.updateNeighborsAt(pos, state.getBlock());
                }
            }
        }
    }

    private void computeFaceOutput(int width, int height) {
        java.util.Arrays.fill(faceOutput, 0);
        if (edgeGrid == null) return;

        for (int c = 0; c < width; c++) {
            faceOutput[E_UP] = Math.max(faceOutput[E_UP], edgeGrid.vEdges[c]);
        }
        for (int c = 0; c < width; c++) {
            faceOutput[E_DOWN] = Math.max(faceOutput[E_DOWN], edgeGrid.vEdges[height * width + c]);
        }
        for (int r = 0; r < height; r++) {
            faceOutput[E_LEFT] = Math.max(faceOutput[E_LEFT], edgeGrid.hEdges[r * (width + 1)]);
        }
        for (int r = 0; r < height; r++) {
            faceOutput[E_RIGHT] = Math.max(faceOutput[E_RIGHT], edgeGrid.hEdges[r * (width + 1) + width]);
        }
    }

    public int getBoundarySignal(int internalDir) {
        return faceOutput[internalDir];
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
        int height = (size + width - 1) / width;
        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = getEffectiveInput(slot, inputDir, width, height) > 0;
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

    private int getEffectiveInput(int slot, int dir, int width, int height) {
        int edgeSignal = edgeGrid.get(slot, dir);
        int r = slot / width;
        int c = slot % width;
        if (dir == E_UP && r == 0) return Math.max(edgeSignal, faceInput[E_UP]);
        if (dir == E_DOWN && r == height - 1) return Math.max(edgeSignal, faceInput[E_DOWN]);
        if (dir == E_LEFT && c == 0) return Math.max(edgeSignal, faceInput[E_LEFT]);
        if (dir == E_RIGHT && c == width - 1) return Math.max(edgeSignal, faceInput[E_RIGHT]);
        return edgeSignal;
    }

    private int computeComparatorOutput(int slot, LivingComparatorData data,
            ContainerContext context, int size, int width) {
        int height = (size + width - 1) / width;
        int inputDir = edgeIndex(data.direction().opposite());
        int signalA = getEffectiveInput(slot, inputDir, width, height);

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
     * 锁定方中继器在 perpDir 方向侧面时，它必须朝向被锁定方（即 opposite of perpDir）。
     */
    private static Pos2D requiredDir(int perpDir) {
        return switch (perpDir) {
            case E_LEFT -> Pos2D.RIGHT;
            case E_RIGHT -> Pos2D.LEFT;
            case E_UP -> Pos2D.DOWN;
            case E_DOWN -> Pos2D.UP;
            default -> Pos2D.NONE;
        };
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