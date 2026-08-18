package com.qiqi.li.living.domain.redstone;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

public class ContainerRedstoneData {

    private static final int PROPAGATION_INTERVAL = 2;

    private int[] signalStrength;
    private int[] prevSignalStrength;

    private int tickCounter;
    private boolean processedThisTick;

    public ContainerRedstoneData(int size) {
        this.signalStrength = new int[size];
        this.prevSignalStrength = new int[size];
        this.tickCounter = 1;
        this.processedThisTick = false;
    }

    public static int getSignalCap(int stackCount) {
        return stackCount == 1 ? 15 : stackCount * stackCount;
    }

    public int getSignal(int slot) {
        return slot >= 0 && slot < signalStrength.length ? signalStrength[slot] : 0;
    }

    public int getTickCounter() {
        return tickCounter;
    }

    public int getSize() {
        return signalStrength.length;
    }

    private void reset() {
        int[] temp = prevSignalStrength;
        prevSignalStrength = signalStrength;
        signalStrength = temp;
        for (int i = 0; i < signalStrength.length; i++) {
            signalStrength[i] = 0;
        }
    }

    public void calculate(ContainerContext context, TickContext tick) {
        if (processedThisTick) return;
        processedThisTick = true;
        tickCounter++;
        if (tickCounter % PROPAGATION_INTERVAL != 0) return;

        int size = context.getSize();
        int width = context.getWidth();

        Set<Integer> torchSlots = tick.getFunctionSlots(LivingRedstoneTorchFunction.ID);
        Set<Integer> dustSlots = tick.getFunctionSlots(LivingRedstoneFunction.ID);
        Set<Integer> buttonSlots = tick.getFunctionSlots(LivingButtonFunction.ID);
        Set<Integer> leverSlots = tick.getFunctionSlots(LivingLeverFunction.ID);
        Set<Integer> lampSlots = tick.getFunctionSlots(LivingRedstoneLampFunction.ID);
        Set<Integer> repeaterSlots = tick.getFunctionSlots(LivingRepeaterFunction.ID);
        Set<Integer> comparatorSlots = tick.getFunctionSlots(LivingComparatorFunction.ID);

        boolean hasAny = !torchSlots.isEmpty() || !dustSlots.isEmpty()
            || !buttonSlots.isEmpty() || !leverSlots.isEmpty() || !lampSlots.isEmpty()
            || !repeaterSlots.isEmpty() || !comparatorSlots.isEmpty();
        if (!hasAny) return;

        if (signalStrength.length != size) {
            signalStrength = new int[size];
            prevSignalStrength = new int[size];
        }
        reset();

        // ===== Phase 0: countdown delays, detect power-off using prevSignalStrength =====
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (!data.powered()) continue;

            Pos2D inputDir = LivingRepeaterFunction.getInputDirection(data.direction());
            int inputSlot = resolveSlot(slot, inputDir, size, width);
            boolean hasInput = inputSlot >= 0 && prevSignalStrength[inputSlot] > 0;

            if (!hasInput) {
                data = data.withPowered(false).withDelayTimer(0);
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            } else if (data.delayTimer() > 0) {
                int newTimer = data.delayTimer() - 1;
                data = data.withDelayTimer(newTimer);
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

        // ===== Phase 1: collect all signal sources =====
        Queue<Integer> queue = new ArrayDeque<>();

        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            if (data.isLit()) {
                signalStrength[slot] = getSignalCap(stack.getCount());
                queue.add(slot);
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (data.pressed()) {
                signalStrength[slot] = getSignalCap(stack.getCount());
                queue.add(slot);
            }
        }

        for (int slot : leverSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingLeverData data = LivingItemManager.getLeverData(stack);
            if (data.powered()) {
                signalStrength[slot] = getSignalCap(stack.getCount());
                queue.add(slot);
            }
        }

        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (data.powered() && data.delayTimer() == 0) {
                signalStrength[slot] = getSignalCap(stack.getCount());
                queue.add(slot);
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, size, width, context);
            if (output > 0) {
                signalStrength[slot] = output;
                queue.add(slot);
            }
        }

        // ===== Phase 2: iterative propagation until stable =====
        while (!queue.isEmpty()) {
            int current = queue.poll();
            int currentSignal = signalStrength[current];
            if (currentSignal <= 1) continue;

            boolean isTorch = torchSlots.contains(current);
            boolean isSource = isTorch || buttonSlots.contains(current)
                || leverSlots.contains(current);

            Pos2D outDir = getOutputDirection(current, repeaterSlots, comparatorSlots, context);

            int[] neighbors = ContainerContext.getNeighbors(current, size, width);
            for (int neighbor : neighbors) {
                ItemStack neighborStack = context.getItem(neighbor);
                if (neighborStack.isEmpty()) continue;
                if (!isRedstoneComponent(neighbor, dustSlots, repeaterSlots, comparatorSlots)) continue;

                if (outDir != null) {
                    int expected = resolveSlot(current, outDir, size, width);
                    if (neighbor != expected) continue;
                }

                if (isTorch) {
                    ItemStack torchStack = context.getItem(current);
                    LivingRedstoneTorchData torchData = LivingItemManager.getRedstoneTorchData(torchStack);
                    Pos2D inputDir = LivingRedstoneTorchFunction.getInputDirection(torchData.direction());
                    int inputSlot = resolveSlot(current, inputDir, size, width);
                    if (neighbor == inputSlot) continue;
                }

                int newSignal = isSource ? currentSignal : currentSignal - 1;
                int cap = getSignalCap(neighborStack.getCount());
                newSignal = Math.min(newSignal, cap);

                if (newSignal > signalStrength[neighbor]) {
                    signalStrength[neighbor] = newSignal;
                    queue.add(neighbor);
                }
            }
        }

        // ===== Phase 3: re-check inputs for components that may have received new signals =====
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (data.powered() && data.delayTimer() == 0) continue;

            Pos2D inputDir = LivingRepeaterFunction.getInputDirection(data.direction());
            int inputSlot = resolveSlot(slot, inputDir, size, width);
            boolean hasInput = inputSlot >= 0 && signalStrength[inputSlot] > 0;

            if (hasInput && !data.powered()) {
                LivingItemManager.setRepeaterData(stack, data.withPowered(true).withDelayTimer(data.delay()));
                context.syncSlotToClients(slot, stack);
            } else if (!hasInput && data.powered()) {
                LivingItemManager.setRepeaterData(stack, data.withPowered(false).withDelayTimer(0));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, size, width, context);
            boolean newPowered = output > 0;
            if (data.powered() != newPowered) {
                LivingItemManager.setComparatorData(stack, data.withPowered(newPowered));
                context.syncSlotToClients(slot, stack);
            }
        }

        // ===== Phase 4: update display states =====
        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            Pos2D inputDir = LivingRedstoneTorchFunction.getInputDirection(data.direction());
            int inputSlot = resolveSlot(slot, inputDir, size, width);
            boolean hasInput = inputSlot >= 0 && signalStrength[inputSlot] > 0;
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

            int signal = signalStrength[slot];
            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            if (data.signalStrength() != signal || data.isPowered() != (signal > 0)) {
                LivingItemManager.setRedstoneData(stack, data.withSignal(signal).withPowered(signal > 0));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : lampSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            boolean hasSignal = false;
            int[] neighbors = ContainerContext.getNeighbors(slot, size, width);
            for (int neighbor : neighbors) {
                if (signalStrength[neighbor] > 0) {
                    hasSignal = true;
                    break;
                }
            }

            LivingRedstoneLampData data = LivingItemManager.getLampData(stack);
            if (data.lit() != hasSignal) {
                LivingItemManager.setLampData(stack, data.withLit(hasSignal));
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    private boolean isRedstoneComponent(int slot, Set<Integer> dustSlots,
            Set<Integer> repeaterSlots, Set<Integer> comparatorSlots) {
        return dustSlots.contains(slot) || repeaterSlots.contains(slot) || comparatorSlots.contains(slot);
    }

    private Pos2D getOutputDirection(int slot, Set<Integer> repeaterSlots,
            Set<Integer> comparatorSlots, ContainerContext context) {
        if (repeaterSlots.contains(slot)) {
            ItemStack stack = context.getItem(slot);
            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            return data.direction();
        }
        if (comparatorSlots.contains(slot)) {
            ItemStack stack = context.getItem(slot);
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            return data.direction();
        }
        return null;
    }

    private int computeComparatorOutput(int slot, LivingComparatorData data,
            int size, int width, ContainerContext context) {
        Pos2D inputDir = LivingComparatorFunction.getInputDirection(data.direction());
        int inputASlot = resolveSlot(slot, inputDir, size, width);
        int signalA = inputASlot >= 0 ? signalStrength[inputASlot] : 0;

        int[] sideSlots = getPerpendicularNeighbors(slot, data.direction(), size, width);
        int signalB = 0;
        for (int side : sideSlots) {
            if (side >= 0) {
                signalB = Math.max(signalB, signalStrength[side]);
            }
        }

        int output;
        if (data.subtractMode()) {
            output = Math.max(0, signalA - signalB);
        } else {
            output = signalA >= signalB ? signalA : 0;
        }

        int cap = getSignalCap(context.getItem(slot).getCount());
        return Math.min(output, cap);
    }

    private static int resolveSlot(int slot, Pos2D dir, int size, int width) {
        int col = slot % width;
        int row = slot / width;
        int newCol = col + dir.x();
        int newRow = row + dir.y();
        if (newCol < 0 || newCol >= width || newRow < 0) return -1;
        int result = newRow * width + newCol;
        return result < size ? result : -1;
    }

    private static int[] getPerpendicularNeighbors(int slot, Pos2D direction, int size, int width) {
        if (direction.x() == 0) {
            return new int[] {
                resolveSlot(slot, Pos2D.LEFT, size, width),
                resolveSlot(slot, Pos2D.RIGHT, size, width)
            };
        } else {
            return new int[] {
                resolveSlot(slot, Pos2D.UP, size, width),
                resolveSlot(slot, Pos2D.DOWN, size, width)
            };
        }
    }
}