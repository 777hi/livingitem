package com.qiqi.li.living.core.components;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.function.LivingChestFunction;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class InternalStorageComponent implements ILivingComponent {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String ID = "internal_storage";
    public static final String KEY_USED_SLOTS = "_us";
    public static final String KEY_BYTE_USAGE = "_bu";
    public static final String KEY_CONTAINER_HASH = "_ch";
    public static final int MAX_STORAGE_BYTES = 16384;

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        if (ctx.level().isClientSide()) return;
        int prevHash = state.getInt(KEY_CONTAINER_HASH, 0);
        int curHash = containerHash(hostStack);
        state.setInt(KEY_USED_SLOTS, countUsedSlots(hostStack));
        if (curHash != prevHash) {
            state.setIntSilent(KEY_CONTAINER_HASH, curHash);
            updateByteUsage(hostStack, state, ctx.level());
        }
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder) {
        int usedBytes = state.getInt(KEY_BYTE_USAGE, 0);
        if (usedBytes > 0) {
            String usedStr = formatByteSize(usedBytes);
            String maxStr = formatByteSize(MAX_STORAGE_BYTES);
            tooltipAdder.accept(net.minecraft.network.chat.Component.translatable(
                "tooltip.livingitem.chest.bytes", usedStr, maxStr));
        }
    }

    public static String formatByteSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        return String.format("%.1fKB", bytes / 1024.0);
    }

    public static int getCurrentByteUsage(ItemStack chestStack) {
        return getCurrentByteUsage(chestStack, null);
    }

    public static int getCurrentByteUsage(ItemStack chestStack, net.minecraft.core.HolderLookup.Provider registries) {
        if (chestStack.isEmpty()) return 0;
        if (registries != null) {
            try {
                CompoundTag tag = (CompoundTag) chestStack.saveOptional(registries);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (DataOutputStream dos = new DataOutputStream(baos)) {
                    NbtIo.write(tag, dos);
                }
                return baos.size();
            } catch (Exception e) {
                return estimateByteUsage(chestStack);
            }
        }
        return estimateByteUsage(chestStack);
    }

    private static int estimateByteUsage(ItemStack chestStack) {
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return 0;
        return countUsedSlots(chestStack) * 64;
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert) {
        if (chestStack == itemToInsert) return false;
        if (chestStack.getCount() > 1) return false;
        return !isByteFull(chestStack);
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert, net.minecraft.core.HolderLookup.Provider registries) {
        if (chestStack == itemToInsert) return false;
        if (chestStack.getCount() > 1) return false;
        return !isByteFull(chestStack, registries);
    }

    public static List<ItemStack> getItems(ItemStack chestStack) {
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) {
            return createEmptySlots(LivingChestFunction.CHEST_SLOTS);
        }
        NonNullList<ItemStack> list = NonNullList.withSize(LivingChestFunction.CHEST_SLOTS, ItemStack.EMPTY);
        contents.copyInto(list);
        return new ArrayList<>(list);
    }

    public static void setItems(ItemStack chestStack, List<ItemStack> items) {
        NonNullList<ItemStack> list = NonNullList.withSize(LivingChestFunction.CHEST_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(items.size(), LivingChestFunction.CHEST_SLOTS); i++) {
            list.set(i, items.get(i).copy());
        }
        chestStack.set(net.minecraft.core.component.DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert) {
        return insertItem(chestStack, itemToInsert, null);
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert, net.minecraft.core.HolderLookup.Provider registries) {
        if (chestStack.getCount() > 1) {
            return false;
        }
        if (chestStack == itemToInsert) {
            return false;
        }
        if (!canInsert(chestStack, itemToInsert, registries)) {
            return false;
        }

        List<ItemStack> items = getItems(chestStack);
        boolean modified = false;

        for (int i = 0; i < items.size() && !itemToInsert.isEmpty(); i++) {
            ItemStack slotItem = items.get(i);

            if (slotItem.isEmpty()) {
                items.set(i, itemToInsert.copy());
                itemToInsert.setCount(0);
                modified = true;
            } else if (ItemStack.isSameItemSameComponents(slotItem, itemToInsert)) {
                int spaceAvailable = slotItem.getMaxStackSize() - slotItem.getCount();
                int transferAmount = Math.min(itemToInsert.getCount(), spaceAvailable);
                if (transferAmount > 0) {
                    slotItem.grow(transferAmount);
                    itemToInsert.shrink(transferAmount);
                    modified = true;
                }
            }
        }

        if (modified) {
            setItems(chestStack, items);
        }
        return itemToInsert.isEmpty();
    }

    public static ItemStack extractItem(ItemStack chestStack, int amount) {
        if (chestStack.getCount() > 1) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> items = getItems(chestStack);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        boolean modified = false;

        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slotItem = items.get(i);
            if (slotItem.isEmpty()) continue;

            if (result.isEmpty()) {
                int toExtract = Math.min(remaining, slotItem.getCount());
                result = slotItem.copyWithCount(toExtract);
                slotItem.shrink(toExtract);
                remaining -= toExtract;
                modified = true;
            } else if (ItemStack.isSameItemSameComponents(result, slotItem)) {
                int toExtract = Math.min(remaining,
                    Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                if (toExtract > 0) {
                    result.grow(toExtract);
                    slotItem.shrink(toExtract);
                    remaining -= toExtract;
                    modified = true;
                }
            }

            if (slotItem.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }

        if (modified) {
            setItems(chestStack, items);
        }
        return result;
    }

    public static ItemStack extractItem(ItemStack chestStack, ItemStack target, int amount) {
        if (chestStack.getCount() > 1) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> items = getItems(chestStack);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        boolean modified = false;

        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slotItem = items.get(i);
            if (slotItem.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(target, slotItem)) continue;

            int toExtract;
            if (result.isEmpty()) {
                toExtract = Math.min(remaining, slotItem.getCount());
                result = slotItem.copyWithCount(toExtract);
            } else {
                toExtract = Math.min(remaining,
                    Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                if (toExtract <= 0) {
                    remaining = 0;
                    break;
                }
                result.grow(toExtract);
            }

            slotItem.shrink(toExtract);
            remaining -= toExtract;
            modified = true;

            if (slotItem.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
        }

        if (modified) {
            setItems(chestStack, items);
        }
        return result;
    }

    public static boolean isStorageFull(ItemStack chestStack) {
        if (chestStack.getCount() > 1) {
            return true;
        }
        return countUsedSlots(chestStack) >= LivingChestFunction.CHEST_SLOTS;
    }

    public static boolean isByteFull(ItemStack chestStack) {
        return getCurrentByteUsage(chestStack) >= MAX_STORAGE_BYTES;
    }

    public static boolean isByteFull(ItemStack chestStack, net.minecraft.core.HolderLookup.Provider registries) {
        return getCurrentByteUsage(chestStack, registries) >= MAX_STORAGE_BYTES;
    }

    public static boolean isStorageEmpty(ItemStack chestStack) {
        if (chestStack.getCount() > 1) {
            return true;
        }
        return countUsedSlots(chestStack) == 0;
    }

    public static void clearStorage(ItemStack chestStack) {
        chestStack.remove(net.minecraft.core.component.DataComponents.CONTAINER);
    }

    private static int countUsedSlots(ItemStack chestStack) {
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return 0;
        NonNullList<ItemStack> list = NonNullList.withSize(LivingChestFunction.CHEST_SLOTS, ItemStack.EMPTY);
        contents.copyInto(list);
        int used = 0;
        for (ItemStack item : list) {
            if (!item.isEmpty()) used++;
        }
        return used;
    }

    private static int containerHash(ItemStack chestStack) {
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        return contents == null ? 0 : contents.hashCode();
    }

    private static void updateByteUsage(ItemStack chestStack, ComponentState state, net.minecraft.world.level.Level level) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            int usage = getCurrentByteUsage(chestStack, serverLevel.registryAccess());
            state.setIntSilent(KEY_BYTE_USAGE, usage);
        } else {
            int usage = estimateByteUsage(chestStack);
            state.setIntSilent(KEY_BYTE_USAGE, usage);
        }
    }

    private static List<ItemStack> createEmptySlots(int capacity) {
        List<ItemStack> slots = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            slots.add(ItemStack.EMPTY);
        }
        return slots;
    }
}