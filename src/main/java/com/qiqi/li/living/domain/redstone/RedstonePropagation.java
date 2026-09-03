package com.qiqi.li.living.domain.redstone;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.model.Pos2D;

/**
 * 红石传播阶段执行器 —— 持有槽位网格与外部输入引用，负责 6 个 phase 的执行。
 *
 * <p>薄状态对象：在 {@link ContainerRedstoneData#calculate} 开头创建，用完即弃。
 * 所有 phase 方法共享同一个 {@code edgeGrid} / {@code slotMask} / {@code faceInput} 引用，
 * 依赖关系通过构造器参数显式化。</p>
 */
class RedstonePropagation {

    private static final int TICKS_PER_REPEATER_STEP = 2;
    private static final int E_UP = ContainerRedstoneData.EDGE_UP;
    private static final int E_DOWN = ContainerRedstoneData.EDGE_DOWN;
    private static final int E_LEFT = ContainerRedstoneData.EDGE_LEFT;
    private static final int E_RIGHT = ContainerRedstoneData.EDGE_RIGHT;
    private static final Pos2D[] DIR_POS = {Pos2D.UP, Pos2D.DOWN, Pos2D.LEFT, Pos2D.RIGHT};

    // ── 传播状态引用（由构造器注入，与 ContainerRedstoneData 共享同一可变实例）──
    private final ContainerRedstoneData.EdgeGrid edgeGrid;
    private final int[] slotMask;
    private final int[] faceInput;
    private final ContainerSnapshot currentSnapshot;
    private final ContainerContext context;
    private final int size;
    private final int width;
    private final int height;

    RedstonePropagation(
            ContainerRedstoneData.EdgeGrid edgeGrid,
            int[] slotMask,
            int[] faceInput,
            ContainerSnapshot currentSnapshot,
            ContainerContext context,
            int size,
            int width) {
        this.edgeGrid = edgeGrid;
        this.slotMask = slotMask;
        this.faceInput = faceInput;
        this.currentSnapshot = currentSnapshot;
        this.context = context;
        this.size = size;
        this.width = width;
        this.height = (size + width - 1) / width;
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 入口（由 calculate() 按顺序调用）
    // ═══════════════════════════════════════════════════════════════

    void phase0CountdownDelays(Set<Integer> repeaterSlots, Set<Integer> buttonSlots) {
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
                int newTimer = data.pulseTimer() - 1;
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

    Queue<Integer> phase1CollectSources(
            Set<Integer> torchSlots, Set<Integer> buttonSlots, Set<Integer> leverSlots,
            Set<Integer> repeaterSlots, Set<Integer> comparatorSlots, Set<Integer> dustSlots,
            Set<Integer> redstoneBlockSlots, Set<Integer> grateSlots) {
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
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
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
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
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
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
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
            int neighbor = ContainerContext.resolveNeighbor(slot, outDir, size, width);
            if (cap > edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, cap);
                if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
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
            int neighbor = ContainerContext.resolveNeighbor(slot, outDir, size, width);
            if (output > edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, output);
                if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
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
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : grateSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingGrateData data = LivingItemManager.getGrateData(stack);
            if (data.sumSignal() <= 0) continue;

            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (neighbor < 0 || neighbor >= size) continue;
                if (!is(neighbor, ContainerRedstoneData.BIT_COPPER)) continue;
                if (getOxidationLevel(slot) != getOxidationLevel(neighbor)) continue;

                if (data.sumSignal() > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, data.sumSignal());
                    queue.add(neighbor);
                }
            }
        }

        return queue;
    }

    void phase2Propagation(Queue<Integer> queue) {
        while (!queue.isEmpty()) {
            int current = queue.poll();

            if (is(current, ContainerRedstoneData.BIT_DUST)) {
                ItemStack stack = context.getItem(current);
                if (stack.isEmpty()) continue;

                int maxInput = maxInputOfSlot(current);
                if (maxInput <= 1) continue;

                int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));

                for (int dir = 0; dir < 4; dir++) {
                    int neighbor = ContainerContext.resolveNeighbor(current, dir, size, width);
                    int currentEdge = edgeGrid.get(current, dir);
                    if (output <= currentEdge) continue;
                    edgeGrid.set(current, dir, output);

                    if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
                continue;
            }

            if (is(current, ContainerRedstoneData.BIT_COPPER)) {
                if (is(current, ContainerRedstoneData.BIT_GRATE)) continue;

                ItemStack stack = context.getItem(current);
                if (stack.isEmpty()) continue;

                int maxInput = maxInputOfSlot(current);
                int cap = getSignalCap(stack.getCount());
                int output = Math.min(maxInput, cap);

                if (is(current, ContainerRedstoneData.BIT_CHISELED)) {
                    ItemStack chiseledStack = context.getItem(current);
                    LivingCutCopperData data = LivingItemManager.getCutCopperData(chiseledStack);
                    int inputEdge = edgeIndex(data.inputDir());
                    int outputEdge = edgeIndex(data.outputDir());
                    int chiseledInput = inputAt(current, inputEdge);
                    int chiseledCap = getSignalCap(chiseledStack.getCount());
                    int chiseledOutput = Math.min(chiseledInput, chiseledCap);
                    propagateDir(current, outputEdge, chiseledOutput, queue);
                    continue;
                }

                if (is(current, ContainerRedstoneData.BIT_CUT)) {
                    int maxHInput = Math.max(
                        inputAt(current, E_LEFT),
                        inputAt(current, E_RIGHT));
                    int maxVInput = Math.max(
                        inputAt(current, E_UP),
                        inputAt(current, E_DOWN));
                    int outputH = Math.min(maxHInput, cap);
                    int outputV = Math.min(maxVInput, cap);

                    propagateDir(current, E_LEFT, outputH, queue);
                    propagateDir(current, E_RIGHT, outputH, queue);
                    propagateDir(current, E_UP, outputV, queue);
                    propagateDir(current, E_DOWN, outputV, queue);
                    continue;
                }

                for (int dir = 0; dir < 4; dir++) {
                    propagateDir(current, dir, output, queue);
                }
            }
        }
    }

    void phase3RecheckInputs(
            Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> grateSlots, Set<Integer> bulbSlots) {
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);

            boolean locked = checkRepeaterLocked(slot, data);
            if (data.locked() != locked) {
                data = data.withLocked(locked);
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
            if (locked) continue;

            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = getEffectiveInput(slot, inputDir) > 0;

            if (data.powered() && data.delayTimer() > 0) continue;

            if (hasInput && !data.powered()) {
                LivingItemManager.setRepeaterData(stack,
                    data.withPowered(true).withDelayTimer(data.delay() * TICKS_PER_REPEATER_STEP));
                context.syncSlotToClients(slot, stack);
            } else if (hasInput && data.powered() && data.delayTimer() < 0) {
                LivingItemManager.setRepeaterData(stack, data.withDelayTimer(0));
                context.syncSlotToClients(slot, stack);
            } else if (!hasInput && data.powered() && data.delayTimer() == 0) {
                LivingItemManager.setRepeaterData(stack,
                    data.withDelayTimer(-data.delay() * TICKS_PER_REPEATER_STEP));
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

        for (int slot : grateSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int sum = 0;
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (neighbor >= 0 && neighbor < size && is(neighbor, ContainerRedstoneData.BIT_COPPER)) continue;
                sum += inputAt(slot, dir);
            }
            int cap = getSignalCap(stack.getCount());
            int result = Math.min(sum, cap);

            LivingGrateData data = LivingItemManager.getGrateData(stack);
            if (data.sumSignal() != result) {
                LivingItemManager.setGrateData(stack, data.withSumSignal(result));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : bulbSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingCopperBulbData data = LivingItemManager.getCopperBulbData(stack);
            boolean hasInput = anyInputOfSlot(slot);
            boolean changed = false;

            if (hasInput && !data.prevInput()) {
                if (data.recordedSignal() == 0) {
                    int maxInput = maxInputOfSlot(slot);
                    int cap = getSignalCap(stack.getCount());
                    int recorded = Math.min(maxInput, cap);
                    data = data.withRecordedSignal(recorded);
                } else {
                    data = data.withRecordedSignal(0);
                }
                changed = true;
            }
            if (hasInput != data.prevInput()) {
                data = data.withPrevInput(hasInput);
                changed = true;
            }

            if (changed) {
                LivingItemManager.setCopperBulbData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    void phase4PowerConductors(
            Set<Integer> torchSlots, Set<Integer> buttonSlots, Set<Integer> leverSlots,
            Set<Integer> repeaterSlots, Set<Integer> comparatorSlots, Set<Integer> dustSlots,
            Set<Integer> redstoneBlockSlots, Set<Integer> copperSlots) {
        Queue<Integer> secondQueue = new ArrayDeque<>();

        for (int slot : dustSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            byte conn = data.connections();
            int maxInput = maxInputOfSlot(slot);
            if (maxInput <= 1) continue;

            int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));
            for (int dir = 0; dir < 4; dir++) {
                if ((conn & (1 << dir)) == 0) continue;
                powerConductiveNeighbor(slot, output, dir, secondQueue);
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
                powerConductiveNeighbor(slot, cap, dir, secondQueue);
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
                powerConductiveNeighbor(slot, cap, dir, secondQueue);
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
                powerConductiveNeighbor(slot, cap, dir, secondQueue);
            }
        }

        for (int slot : redstoneBlockSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                powerConductiveNeighbor(slot, cap, dir, secondQueue);
            }
        }

        // ── 元件（中继器 / 比较器）输出：迭代到稳定 ──
        int maxElementIterations = size + 2;
        for (int iter = 0; iter < maxElementIterations; iter++) {
            boolean changed = false;

            for (int slot : repeaterSlots) {
                if (slot < 0 || slot >= size) continue;
                ItemStack stack = context.getItem(slot);
                if (stack.isEmpty()) continue;
                LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);

                int output = (data.powered() && data.delayTimer() <= 0)
                    ? getSignalCap(stack.getCount()) : 0;
                int outDir = edgeIndex(data.direction());
                int prev = edgeGrid.get(slot, outDir);
                if (prev != output) {
                    edgeGrid.set(slot, outDir, output);
                    changed = true;
                }
                if (output > 0) {
                    powerConductiveNeighbor(slot, output, outDir, secondQueue);
                }
            }

            for (int slot : comparatorSlots) {
                if (slot < 0 || slot >= size) continue;
                ItemStack stack = context.getItem(slot);
                if (stack.isEmpty()) continue;
                LivingComparatorData data = LivingItemManager.getComparatorData(stack);
                int output = computeComparatorOutput(slot, data);
                int outDir = edgeIndex(data.direction());
                int prev = edgeGrid.get(slot, outDir);
                if (prev != output) {
                    edgeGrid.set(slot, outDir, output);
                    changed = true;
                }
                if (output > 0) {
                    powerConductiveNeighbor(slot, output, outDir, secondQueue);
                }
            }

            if (!changed) break;
        }

        for (int slot : copperSlots) {
            if (slot < 0 || slot >= size) continue;
            if (is(slot, ContainerRedstoneData.BIT_GRATE)) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int maxInput = maxInputOfSlot(slot);
            if (maxInput <= 0) continue;

            int cap = getSignalCap(stack.getCount());
            int output = Math.min(maxInput, cap);

            if (is(slot, ContainerRedstoneData.BIT_CHISELED)) {
                LivingCutCopperData data = LivingItemManager.getCutCopperData(stack);
                int inputEdge = edgeIndex(data.inputDir());
                int outputEdge = edgeIndex(data.outputDir());
                int chiseledInput = inputAt(slot, inputEdge);
                int chiseledCap = getSignalCap(stack.getCount());
                int chiseledOutput = Math.min(chiseledInput, chiseledCap);
                powerConductiveNeighbor(slot, chiseledOutput, outputEdge, secondQueue);
            } else if (is(slot, ContainerRedstoneData.BIT_CUT)) {
                int maxHInput = Math.max(
                    inputAt(slot, E_LEFT),
                    inputAt(slot, E_RIGHT));
                int maxVInput = Math.max(
                    inputAt(slot, E_UP),
                    inputAt(slot, E_DOWN));
                powerConductiveNeighbor(slot, Math.min(maxHInput, cap), E_LEFT, secondQueue);
                powerConductiveNeighbor(slot, Math.min(maxHInput, cap), E_RIGHT, secondQueue);
                powerConductiveNeighbor(slot, Math.min(maxVInput, cap), E_UP, secondQueue);
                powerConductiveNeighbor(slot, Math.min(maxVInput, cap), E_DOWN, secondQueue);
            } else {
                for (int dir = 0; dir < 4; dir++) {
                    powerConductiveNeighbor(slot, output, dir, secondQueue);
                }
            }
        }

        if (!secondQueue.isEmpty()) {
            phase2Propagation(secondQueue);
        }
    }

    void phase5UpdateDisplay(
            Set<Integer> torchSlots, Set<Integer> dustSlots,
            Set<Integer> lampSlots, Set<Integer> copperSlots) {
        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = getEffectiveInput(slot, inputDir) > 0;
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

            int maxSignal = maxInputOfSlot(slot);
            byte conn = computeDustConnections(slot);
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

            boolean hasSignal = anyInputOfSlot(slot);
            LivingRedstoneLampData data = LivingItemManager.getLampData(stack);
            if (data.lit() != hasSignal) {
                LivingItemManager.setLampData(stack, data.withLit(hasSignal));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : copperSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int maxSignal = maxInputOfSlot(slot);
            LivingCopperSignalData sigData = LivingItemManager.getCopperSignal(stack);
            if (sigData.signalStrength() != maxSignal) {
                LivingItemManager.setCopperSignal(stack, sigData.withSignal(maxSignal));
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 传播辅助方法
    // ═══════════════════════════════════════════════════════════════

    private void propagateDir(int slot, int dir, int signal, Queue<Integer> queue) {
        int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
        if (neighbor < 0) return;
        if (is(neighbor, ContainerRedstoneData.BIT_GRATE)) return;
        if (!canConnect(slot, neighbor)) return;

        int currentEdge = edgeGrid.get(slot, dir);
        if (signal <= currentEdge) return;
        edgeGrid.set(slot, dir, signal);

        if (is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
            queue.add(neighbor);
        }
    }

    private void powerConductiveNeighbor(int slot, int signal, int dir, Queue<Integer> secondQueue) {
        int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
        if (neighbor < 0) return;
        if (is(neighbor, ContainerRedstoneData.BIT_TORCH | ContainerRedstoneData.BIT_BUTTON
                | ContainerRedstoneData.BIT_LEVER | ContainerRedstoneData.BIT_REPEATER
                | ContainerRedstoneData.BIT_COMPARATOR | ContainerRedstoneData.BIT_BLOCK)) return;
        ItemStack nStack = context.getItem(neighbor);
        if (nStack.isEmpty()) return;
        boolean conductor = is(neighbor, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)
            || ContainerRedstoneData.isConductiveBlock(nStack);
        if (!conductor) return;

        for (int d2 = 0; d2 < 4; d2++) {
            if (signal > edgeGrid.get(neighbor, d2)) {
                edgeGrid.set(neighbor, d2, signal);
                int n2 = ContainerContext.resolveNeighbor(neighbor, d2, size, width);
                if (n2 >= 0 && is(n2, ContainerRedstoneData.BIT_DUST | ContainerRedstoneData.BIT_COPPER)) {
                    secondQueue.add(n2);
                }
            }
        }
    }

    private boolean checkRepeaterLocked(int slot, LivingRepeaterData data) {
        boolean isVertical = data.direction().equals(Pos2D.UP) || data.direction().equals(Pos2D.DOWN);
        int[] perpDirs = isVertical ? new int[]{E_LEFT, E_RIGHT} : new int[]{E_UP, E_DOWN};

        for (int perpDir : perpDirs) {
            int neighbor = ContainerContext.resolveNeighbor(slot, perpDir, size, width);
            if (is(neighbor, ContainerRedstoneData.BIT_REPEATER)) {
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

    private byte computeDustConnections(int slot) {
        byte conn = 0;
        for (int dir = 0; dir < 4; dir++) {
            int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
            if (neighbor < 0) continue;

            if (is(neighbor, ContainerRedstoneData.BIT_REPEATER | ContainerRedstoneData.BIT_COMPARATOR)) {
                ItemStack ns = context.getItem(neighbor);
                if (!ns.isEmpty()) {
                    Pos2D facing = is(neighbor, ContainerRedstoneData.BIT_REPEATER)
                        ? LivingItemManager.getRepeaterData(ns).direction()
                        : LivingItemManager.getComparatorData(ns).direction();
                    Pos2D d = DIR_POS[dir];
                    if (d.equals(facing) || d.equals(facing.opposite())) {
                        conn |= (1 << dir);
                    }
                }
            } else if (is(neighbor, ContainerRedstoneData.BIT_BUTTON | ContainerRedstoneData.BIT_LEVER
                    | ContainerRedstoneData.BIT_TORCH | ContainerRedstoneData.BIT_DUST
                    | ContainerRedstoneData.BIT_LAMP | ContainerRedstoneData.BIT_BLOCK
                    | ContainerRedstoneData.BIT_COPPER)) {
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

    private int computeComparatorOutput(int slot, LivingComparatorData data) {
        int inputDir = edgeIndex(data.direction().opposite());
        int signalA = getEffectiveInput(slot, inputDir);

        ItemStack comparatorStack = context.getItem(slot);
        int signalCap = getSignalCap(comparatorStack.getCount());

        if (signalA == 0) {
            int backSlot = ContainerContext.resolveNeighbor(slot, inputDir, size, width);
            if (backSlot >= 0) {
                ItemStack backStack = context.getItem(backSlot);
                signalA = LivingComparatorFunction.readComparatorOutput(backStack, signalCap);
            }
        }

        int[] sideDirs = perpendicularEdges(inputDir);
        int signalB = 0;
        for (int sideDir : sideDirs) {
            int edgeSignal = inputAt(slot, sideDir);
            if (edgeSignal > 0) {
                signalB = Math.max(signalB, edgeSignal);
            } else {
                int sideSlot = ContainerContext.resolveNeighbor(slot, sideDir, size, width);
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

    // ═══════════════════════════════════════════════════════════════
    // 网格/输入查询
    // ═══════════════════════════════════════════════════════════════

    private int inputAt(int slot, int dir) {
        int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
        if (neighbor < 0) return faceInput[dir];
        return edgeGrid.get(neighbor, oppositeDir(dir));
    }

    private int maxInputOfSlot(int slot) {
        int max = 0;
        for (int dir = 0; dir < 4; dir++) max = Math.max(max, inputAt(slot, dir));
        return max;
    }

    private boolean anyInputOfSlot(int slot) {
        for (int dir = 0; dir < 4; dir++) if (inputAt(slot, dir) > 0) return true;
        return false;
    }

    private int getEffectiveInput(int slot, int dir) {
        return inputAt(slot, dir);
    }

    // ═══════════════════════════════════════════════════════════════
    // 槽位类型判定
    // ═══════════════════════════════════════════════════════════════

    private boolean is(int slot, int mask) {
        return slot >= 0 && slot < slotMask.length && (slotMask[slot] & mask) != 0;
    }

    private boolean canConnect(int slot, int neighbor) {
        if (neighbor < 0 || neighbor >= size) return false;
        ItemStack neighborStack = context.getItem(neighbor);
        if (neighborStack.isEmpty()) return false;

        boolean slotIsCopper = is(slot, ContainerRedstoneData.BIT_COPPER);
        boolean neighborIsCopper = is(neighbor, ContainerRedstoneData.BIT_COPPER);
        boolean neighborIsDust = is(neighbor, ContainerRedstoneData.BIT_DUST);

        if (!slotIsCopper && neighborIsCopper) return true;
        if (slotIsCopper && neighborIsDust) return true;

        if (slotIsCopper && neighborIsCopper) {
            return getOxidationLevel(slot) == getOxidationLevel(neighbor);
        }

        return true;
    }

    private int getOxidationLevel(int slot) {
        if (context != null) {
            ItemStack stack = context.getItem(slot);
            if (!stack.isEmpty()) return LivingCopperFunction.getOxidationLevel(stack.getItem());
        }
        if (currentSnapshot != null && slot >= 0 && slot < currentSnapshot.getCapOf().length) {
            int lvl = currentSnapshot.getCapOf(slot);
            if (lvl >= 0) return lvl;
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════
    // 静态工具方法
    // ═══════════════════════════════════════════════════════════════

    static int getSignalCap(int stackCount) {
        return stackCount == 1 ? 15 : stackCount * stackCount;
    }

    static int edgeIndex(Pos2D dir) {
        if (dir.x() == 0) {
            return dir.y() < 0 ? E_UP : E_DOWN;
        } else {
            return dir.x() < 0 ? E_LEFT : E_RIGHT;
        }
    }

    static int oppositeDir(int dir) {
        return switch (dir) {
            case E_UP -> E_DOWN;
            case E_DOWN -> E_UP;
            case E_LEFT -> E_RIGHT;
            case E_RIGHT -> E_LEFT;
            default -> dir;
        };
    }

    static int[] perpendicularEdges(int dir) {
        if (dir == E_UP || dir == E_DOWN) {
            return new int[] {E_LEFT, E_RIGHT};
        } else {
            return new int[] {E_UP, E_DOWN};
        }
    }

    static Pos2D requiredDir(int perpDir) {
        return switch (perpDir) {
            case E_LEFT -> Pos2D.RIGHT;
            case E_RIGHT -> Pos2D.LEFT;
            case E_UP -> Pos2D.DOWN;
            case E_DOWN -> Pos2D.UP;
            default -> Pos2D.NONE;
        };
    }
}