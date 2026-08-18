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

    private int tickCounter;
    private boolean processedThisTick;

    public ContainerRedstoneData(int size) {
        this.signalStrength = new int[size];
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
        }
        reset();

        Queue<Integer> queue = new ArrayDeque<>();
        boolean[] visited = new boolean[size];

        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            if (data.isLit()) {
                int signal = getSignalCap(stack.getCount());
                signalStrength[slot] = signal;
                queue.add(slot);
                visited[slot] = true;
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (data.pressed()) {
                int signal = getSignalCap(stack.getCount());
                signalStrength[slot] = signal;
                queue.add(slot);
                visited[slot] = true;
            }
        }

        for (int slot : leverSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingLeverData data = LivingItemManager.getLeverData(stack);
            if (data.powered()) {
                int signal = getSignalCap(stack.getCount());
                signalStrength[slot] = signal;
                queue.add(slot);
                visited[slot] = true;
            }
        }

        while (!queue.isEmpty()) {
            int current = queue.poll();
            int currentSignal = signalStrength[current];
            if (currentSignal <= 1) continue;

            boolean isTorch = torchSlots.contains(current);
            boolean isButton = buttonSlots.contains(current);
            boolean isLever = leverSlots.contains(current);
            boolean isSource = isTorch || isButton || isLever;

            int[] neighbors = ContainerContext.getNeighbors(current, size, width);
            for (int neighbor : neighbors) {
                if (visited[neighbor]) continue;
                ItemStack neighborStack = context.getItem(neighbor);
                if (neighborStack.isEmpty()) continue;

                if (!dustSlots.contains(neighbor)) continue;

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
                }

                visited[neighbor] = true;
                queue.add(neighbor);
            }
        }

        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            Pos2D inputDir = LivingRepeaterFunction.getInputDirection(data.direction());
            int inputSlot = resolveSlot(slot, inputDir, size, width);
            boolean hasInputSignal = inputSlot >= 0 && signalStrength[inputSlot] > 0;

            if (hasInputSignal && !data.powered()) {
                LivingItemManager.setRepeaterData(stack, data.withPowered(true).withDelayTimer(data.delay()));
                context.syncSlotToClients(slot, stack);
            } else if (!hasInputSignal && data.powered()) {
                LivingItemManager.setRepeaterData(stack, data.withPowered(false).withDelayTimer(0));
                context.syncSlotToClients(slot, stack);
            } else if (data.powered() && data.delayTimer() > 0) {
                int newTimer = data.delayTimer() - 1;
                LivingItemManager.setRepeaterData(stack, data.withDelayTimer(newTimer));
                context.syncSlotToClients(slot, stack);

                if (newTimer == 0) {
                    outputRepeaterSignal(stack, slot, repeaterSlots, dustSlots, size, width, context);
                }
            } else if (data.powered() && data.delayTimer() == 0) {
                outputRepeaterSignal(stack, slot, repeaterSlots, dustSlots, size, width, context);
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
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

            int cap = getSignalCap(stack.getCount());
            output = Math.min(output, cap);

            boolean newPowered = output > 0;
            if (data.powered() != newPowered) {
                LivingItemManager.setComparatorData(stack, data.withPowered(newPowered));
                context.syncSlotToClients(slot, stack);
            }

            if (output > 0) {
                signalStrength[slot] = output;

                Queue<Integer> cmpQueue = new ArrayDeque<>();
                boolean[] cmpVisited = new boolean[size];
                cmpQueue.add(slot);
                cmpVisited[slot] = true;

                while (!cmpQueue.isEmpty()) {
                    int current = cmpQueue.poll();
                    int currentSignal = signalStrength[current];
                    if (currentSignal <= 1) continue;

                    Pos2D outDir = null;
                    if (comparatorSlots.contains(current)) {
                        ItemStack cs = context.getItem(current);
                        LivingComparatorData cd = LivingItemManager.getComparatorData(cs);
                        outDir = cd.direction();
                    }

                    int[] neis = ContainerContext.getNeighbors(current, size, width);
                    for (int neighbor : neis) {
                        if (cmpVisited[neighbor]) continue;
                        ItemStack ns = context.getItem(neighbor);
                        if (ns.isEmpty()) continue;
                        if (!dustSlots.contains(neighbor)) continue;

                        if (outDir != null) {
                            int expectedNeighbor = resolveSlot(current, outDir, size, width);
                            if (neighbor != expectedNeighbor) continue;
                        }

                        int newSignal = currentSignal - 1;
                        int cap2 = getSignalCap(ns.getCount());
                        newSignal = Math.min(newSignal, cap2);

                        if (newSignal > signalStrength[neighbor]) {
                            signalStrength[neighbor] = newSignal;
                        }

                        cmpVisited[neighbor] = true;
                        cmpQueue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            Pos2D facing = data.direction();
            Pos2D inputDir = LivingRedstoneTorchFunction.getInputDirection(facing);

            int inputSlot = resolveSlot(slot, inputDir, size, width);
            boolean hasInputSignal = inputSlot >= 0 && signalStrength[inputSlot] > 0;

            boolean newLit = !hasInputSignal;
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

    private void outputRepeaterSignal(ItemStack stack, int slot, Set<Integer> repeaterSlots,
            Set<Integer> dustSlots, int size, int width, ContainerContext context) {
        int cap = getSignalCap(stack.getCount());
        if (signalStrength == null || signalStrength.length != size) return;
        signalStrength[slot] = cap;

        Queue<Integer> repQueue = new ArrayDeque<>();
        boolean[] repVisited = new boolean[size];
        repQueue.add(slot);
        repVisited[slot] = true;

        while (!repQueue.isEmpty()) {
            int current = repQueue.poll();
            int currentSignal = signalStrength[current];
            if (currentSignal <= 1) continue;

            Pos2D outDir = null;
            if (repeaterSlots.contains(current)) {
                ItemStack rs = context.getItem(current);
                LivingRepeaterData rd = LivingItemManager.getRepeaterData(rs);
                outDir = rd.direction();
            }

            int[] neis = ContainerContext.getNeighbors(current, size, width);
            for (int neighbor : neis) {
                if (repVisited[neighbor]) continue;
                ItemStack ns = context.getItem(neighbor);
                if (ns.isEmpty()) continue;
                if (!dustSlots.contains(neighbor)) continue;

                if (outDir != null) {
                    int expectedNeighbor = resolveSlot(current, outDir, size, width);
                    if (neighbor != expectedNeighbor) continue;
                }

                int newSignal = currentSignal - 1;
                int cap2 = getSignalCap(ns.getCount());
                newSignal = Math.min(newSignal, cap2);

                if (newSignal > signalStrength[neighbor]) {
                    signalStrength[neighbor] = newSignal;
                }

                repVisited[neighbor] = true;
                repQueue.add(neighbor);
            }
        }
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