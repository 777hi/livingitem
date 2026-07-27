package com.qiqi.li.living.core.components;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.function.LivingChestFunction;

public final class InternalStorageComponent {

    public static final String ID = "internal_storage";
    public static final int MAX_STORAGE_BYTES = LivingChestFunction.MAX_STORAGE_BYTES;

    private InternalStorageComponent() {}

    public static List<ItemStack> getItems(ItemStack chestStack) {
        return LivingChestFunction.getItems(chestStack);
    }

    public static void setItems(ItemStack chestStack, List<ItemStack> items) {
        LivingChestFunction.setItems(chestStack, items);
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert) {
        return LivingChestFunction.insertItem(chestStack, itemToInsert);
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert, HolderLookup.Provider registries) {
        return LivingChestFunction.insertItem(chestStack, itemToInsert, registries);
    }

    public static ItemStack extractItem(ItemStack chestStack, int amount) {
        return LivingChestFunction.extractItem(chestStack, amount);
    }

    public static ItemStack extractItem(ItemStack chestStack, ItemStack target, int amount) {
        return LivingChestFunction.extractItem(chestStack, target, amount);
    }

    public static boolean isStorageEmpty(ItemStack stack) {
        return LivingChestFunction.isStorageEmpty(stack);
    }

    public static boolean isStorageFull(ItemStack stack) {
        return LivingChestFunction.isStorageFull(stack);
    }

    public static boolean isByteFull(ItemStack stack) {
        return LivingChestFunction.isByteFull(stack);
    }

    public static boolean isByteFull(ItemStack stack, HolderLookup.Provider registries) {
        return LivingChestFunction.isByteFull(stack, registries);
    }

    public static int getCurrentByteUsage(ItemStack stack) {
        return LivingChestFunction.getCurrentByteUsage(stack);
    }

    public static int getCurrentByteUsage(ItemStack stack, HolderLookup.Provider registries) {
        return LivingChestFunction.getCurrentByteUsage(stack, registries);
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert) {
        return LivingChestFunction.canInsert(chestStack, itemToInsert);
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert, HolderLookup.Provider registries) {
        return LivingChestFunction.canInsert(chestStack, itemToInsert, registries);
    }

    public static void clearStorage(ItemStack chestStack) {
        LivingChestFunction.clearStorage(chestStack);
    }

    public static String formatByteSize(int bytes) {
        return LivingChestFunction.formatByteSize(bytes);
    }
}