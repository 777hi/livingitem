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

        if (torchSlots.isEmpty() && dustSlots.isEmpty()
            && buttonSlots.isEmpty() && leverSlots.isEmpty() && lampSlots.isEmpty()) return;

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
                int signal = stack.getCount() * 15;
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
                int signal = stack.getCount() * 15;
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
                int signal = stack.getCount() * 15;
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
            int torchInputSlot = -1;
            if (isTorch) {
                ItemStack torchStack = context.getItem(current);
                LivingRedstoneTorchData torchData = LivingItemManager.getRedstoneTorchData(torchStack);
                Pos2D inputDir = LivingRedstoneTorchFunction.getInputDirection(torchData.direction());
                torchInputSlot = resolveSlot(current, inputDir, size, width);
            }

            int[] neighbors = ContainerContext.getNeighbors(current, size, width);
            for (int neighbor : neighbors) {
                if (visited[neighbor]) continue;
                if (!dustSlots.contains(neighbor)) continue;
                if (isTorch && neighbor == torchInputSlot) continue;

                ItemStack neighborStack = context.getItem(neighbor);
                if (neighborStack.isEmpty()) continue;

                int newSignal = isTorch ? currentSignal : currentSignal - 1;
                int cap = neighborStack.getCount() * 15;
                newSignal = Math.min(newSignal, cap);

                if (newSignal > signalStrength[neighbor]) {
                    signalStrength[neighbor] = newSignal;
                }

                visited[neighbor] = true;
                queue.add(neighbor);
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

    private static int resolveSlot(int slot, Pos2D dir, int size, int width) {
        int col = slot % width;
        int row = slot / width;
        int newCol = col + dir.x();
        int newRow = row + dir.y();
        if (newCol < 0 || newCol >= width || newRow < 0) return -1;
        int result = newRow * width + newCol;
        return result < size ? result : -1;
    }
}