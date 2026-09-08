package com.qiqi.li.living.domain.chest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public class LivingChestFunction implements LivingItemFunction {

    public static final String ID = "living_chest";
    public static final int CHEST_SLOTS = 27;
    public static final int MAX_STORAGE_BYTES = 16384;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.CHEST) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.chest.status"));

        int usedBytes = getCurrentByteUsage(stack);
        if (usedBytes > 0) {
            String usedStr = formatByteSize(usedBytes);
            String maxStr = formatByteSize(MAX_STORAGE_BYTES);
            double percentage = (usedBytes * 100.0) / MAX_STORAGE_BYTES;
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.chest.bytes", usedStr, maxStr, String.format("%.1f", percentage)));
        }

        int usedSlots = countUsedSlots(stack);
        if (usedSlots > 0) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.chest.slots", usedSlots, CHEST_SLOTS));
        }
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isLivingChest(ItemStack stack) {
        return stack.is(Items.CHEST) && LivingItemManager.isLivingItem(stack);
    }

    public static int getCapacity(ContainerContext ctx) {
        return CHEST_SLOTS;
    }

    public static List<ItemStack> getItems(ItemStack chestStack) {
        ItemContainerContents contents = chestStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) {
            return createEmptySlots(CHEST_SLOTS);
        }
        NonNullList<ItemStack> list = NonNullList.withSize(CHEST_SLOTS, ItemStack.EMPTY);
        contents.copyInto(list);
        return new ArrayList<>(list);
    }

    public static void setItems(ItemStack chestStack, List<ItemStack> items) {
        NonNullList<ItemStack> list = NonNullList.withSize(CHEST_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(items.size(), CHEST_SLOTS); i++) {
            list.set(i, items.get(i).copy());
        }
        chestStack.set(net.minecraft.core.component.DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert) {
        if (chestStack.getCount() > 1 || chestStack == itemToInsert) return false;
        if (!canInsert(chestStack, itemToInsert)) return false;

        List<ItemStack> items = getItems(chestStack);
        boolean modified = false;

        for (int i = 0; i < items.size() && !itemToInsert.isEmpty(); i++) {
            ItemStack slotItem = items.get(i);
            if (slotItem.isEmpty()) {
                items.set(i, itemToInsert.copy());
                itemToInsert.setCount(0);
                modified = true;
            } else if (ItemStack.isSameItemSameComponents(slotItem, itemToInsert)) {
                int space = slotItem.getMaxStackSize() - slotItem.getCount();
                int transfer = Math.min(itemToInsert.getCount(), space);
                if (transfer > 0) {
                    slotItem.grow(transfer);
                    itemToInsert.shrink(transfer);
                    modified = true;
                }
            }
        }

        if (modified) setItems(chestStack, items);
        return itemToInsert.isEmpty();
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert, net.minecraft.core.HolderLookup.Provider registries) {
        return insertItem(chestStack, itemToInsert);
    }

    public static ItemStack extractItem(ItemStack chestStack, int amount) {
        if (chestStack.getCount() > 1) return ItemStack.EMPTY;
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
                int toExtract = Math.min(remaining, Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                if (toExtract > 0) {
                    result.grow(toExtract);
                    slotItem.shrink(toExtract);
                    remaining -= toExtract;
                    modified = true;
                }
            }

            if (slotItem.isEmpty()) items.set(i, ItemStack.EMPTY);
        }

        if (modified) setItems(chestStack, items);
        return result;
    }

    public static ItemStack extractItem(ItemStack chestStack, ItemStack target, int amount) {
        if (chestStack.getCount() > 1) return ItemStack.EMPTY;
        List<ItemStack> items = getItems(chestStack);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        boolean modified = false;

        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slotItem = items.get(i);
            if (slotItem.isEmpty() || !ItemStack.isSameItemSameComponents(target, slotItem)) continue;

            int toExtract;
            if (result.isEmpty()) {
                toExtract = Math.min(remaining, slotItem.getCount());
                result = slotItem.copyWithCount(toExtract);
            } else {
                toExtract = Math.min(remaining, Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                if (toExtract <= 0) { remaining = 0; break; }
                result.grow(toExtract);
            }

            slotItem.shrink(toExtract);
            remaining -= toExtract;
            modified = true;
            if (slotItem.isEmpty()) items.set(i, ItemStack.EMPTY);
        }

        if (modified) setItems(chestStack, items);
        return result;
    }

    public static boolean hasStorage(ItemStack stack) {
        return stack.get(net.minecraft.core.component.DataComponents.CONTAINER) != null;
    }

    public static boolean isStorageEmpty(ItemStack stack) {
        return countUsedSlots(stack) == 0;
    }

    public static boolean isStorageFull(ItemStack stack) {
        return countUsedSlots(stack) >= CHEST_SLOTS;
    }

    public static boolean isByteFull(ItemStack stack) {
        return getCurrentByteUsage(stack) >= MAX_STORAGE_BYTES;
    }

    public static boolean isByteFull(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        return getCurrentByteUsage(stack, registries) >= MAX_STORAGE_BYTES;
    }

    public static int getCurrentByteUsage(ItemStack stack) {
        return calculateExactByteUsage(stack, null);
    }

    public static int getCurrentByteUsage(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        return calculateExactByteUsage(stack, registries);
    }

    private static int calculateExactByteUsage(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        ItemContainerContents contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return 0;

        int totalBytes = 0;
        NonNullList<ItemStack> list = NonNullList.withSize(CHEST_SLOTS, ItemStack.EMPTY);
        contents.copyInto(list);

        for (ItemStack item : list) {
            if (!item.isEmpty()) {
                totalBytes += calculateItemByteUsage(item, registries);
            }
        }
        return totalBytes;
    }

    private static int calculateItemByteUsage(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        if (registries == null) {
            return 64;
        }
        try {
            net.minecraft.nbt.CompoundTag tag = (net.minecraft.nbt.CompoundTag) stack.saveOptional(registries);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {
                net.minecraft.nbt.NbtIo.write(tag, dos);
            }
            return baos.size();
        } catch (Exception e) {
            return 64;
        }
    }

    public static int getMaxStorageBytes() {
        return MAX_STORAGE_BYTES;
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert) {
        if (chestStack == itemToInsert || chestStack.getCount() > 1) return false;
        return !isByteFull(chestStack);
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert, net.minecraft.core.HolderLookup.Provider registries) {
        if (chestStack == itemToInsert || chestStack.getCount() > 1) return false;
        return !isByteFull(chestStack, registries);
    }

    public static void clearStorage(ItemStack chestStack) {
        if (!isLivingChest(chestStack)) return;
        chestStack.set(net.minecraft.core.component.DataComponents.CONTAINER,
            net.minecraft.world.item.component.ItemContainerContents.EMPTY);
    }

    public static void dropAllItems(ItemStack chestStack, Player player) {
        if (!isLivingChest(chestStack)) return;

        List<ItemStack> drops = collectDeactivationDrops(chestStack);
        Level level = player.level();
        BlockPos dropPos = player.blockPosition();
        int droppedCount = 0;

        for (ItemStack drop : drops) {
            level.addFreshEntity(new ItemEntity(
                level, dropPos.getX() + 0.5, dropPos.getY() + 0.5, dropPos.getZ() + 0.5,
                drop));
            droppedCount += drop.getCount();
        }

        clearStorage(chestStack);

        if (droppedCount > 0) {
            LivingItemManager.LOGGER.info("活箱子取消活化：堆叠 {} 个，共掉落 {} 个物品",
                chestStack.getCount(), droppedCount);
        }
    }

    /**
     * 计算取消活化时应返还的掉落清单（纯计算，不改组件，可重复调用）。
     *
     * <p>堆叠数 N 的活箱子语义上是 N 个内容完全相同的箱子——组件相同才允许堆叠，
     * 且堆叠期间存取关闭（{@code count > 1} 全部操作拒绝），该不变量始终成立——
     * 因此取消活化必须返还 N 份内容。每份按槽位原样掉落，
     * 单堆超出物品堆叠上限的总量拆成多个堆，掉落实体数 = ⌈槽位数量 × N / 堆叠上限⌉。</p>
     */
    public static List<ItemStack> collectDeactivationDrops(ItemStack chestStack) {
        if (!isLivingChest(chestStack)) return List.of();

        List<ItemStack> drops = new ArrayList<>();
        int copies = Math.max(1, chestStack.getCount());

        for (ItemStack slotItem : getItems(chestStack)) {
            if (slotItem.isEmpty()) continue;
            long total = (long) slotItem.getCount() * copies;
            while (total > 0) {
                int chunk = (int) Math.min(total, slotItem.getMaxStackSize());
                drops.add(slotItem.copyWithCount(chunk));
                total -= chunk;
            }
        }
        return drops;
    }

    public static int countUsedSlots(ItemStack stack) {
        ItemContainerContents contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) return 0;
        NonNullList<ItemStack> list = NonNullList.withSize(CHEST_SLOTS, ItemStack.EMPTY);
        contents.copyInto(list);
        int used = 0;
        for (ItemStack item : list) {
            if (!item.isEmpty()) used++;
        }
        return used;
    }

    private static List<ItemStack> createEmptySlots(int capacity) {
        List<ItemStack> slots = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    public static String formatByteSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        return String.format("%.1fKB", bytes / 1024.0);
    }
}