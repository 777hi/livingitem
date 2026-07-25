package com.qiqi.li.living.function;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.BaseLivingFunction;
import com.qiqi.li.living.LivingItemManager;

public class LivingChestFunction extends BaseLivingFunction {

    public static final String ID = "living_chest";
    public static final int CHEST_SLOTS = 27;

    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId(ID)
        .withStackMultiplier(false)
        .withOrchestrator(null)
        .addComponent(new InternalStorageComponent());

    @Override
    protected LivingFunctionConfig getConfig() { return CONFIG; }

    @Override
    protected String getTooltipTitleKey() { return "tooltip.livingitem.chest.status"; }

    @Override
    public void addToTooltip(net.minecraft.nbt.CompoundTag functionData,
                             net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag,
                             net.minecraft.world.item.ItemStack stack) {
        if (functionData == null) return;

        tooltipAdder.accept(net.minecraft.network.chat.Component.nullToEmpty(""));
        tooltipAdder.accept(net.minecraft.network.chat.Component.translatable(getTooltipTitleKey()));

        appendComponentTooltips(functionData, tooltipAdder, getConfig());
    }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.CHEST) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    public static boolean isLivingChest(ItemStack stack) {
        return stack.is(Items.CHEST) && LivingItemManager.isLivingItem(stack);
    }

    public static int getCapacity(com.qiqi.li.living.container.ContainerContext ctx) {
        return CHEST_SLOTS;
    }

    public static List<ItemStack> getItems(ItemStack chestStack) {
        return InternalStorageComponent.getItems(chestStack);
    }

    public static void setItems(ItemStack chestStack, List<ItemStack> items) {
        InternalStorageComponent.setItems(chestStack, items);
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert) {
        return InternalStorageComponent.insertItem(chestStack, itemToInsert);
    }

    public static boolean insertItem(ItemStack chestStack, ItemStack itemToInsert, net.minecraft.core.HolderLookup.Provider registries) {
        return InternalStorageComponent.insertItem(chestStack, itemToInsert, registries);
    }

    public static ItemStack extractItem(ItemStack chestStack, int amount) {
        return InternalStorageComponent.extractItem(chestStack, amount);
    }

    public static ItemStack extractItem(ItemStack chestStack, ItemStack target, int amount) {
        return InternalStorageComponent.extractItem(chestStack, target, amount);
    }

    public static boolean hasStorage(ItemStack stack) {
        return stack.get(net.minecraft.core.component.DataComponents.CONTAINER) != null;
    }

    public static boolean isStorageEmpty(ItemStack stack) {
        return InternalStorageComponent.isStorageEmpty(stack);
    }

    public static boolean isStorageFull(ItemStack stack) {
        return InternalStorageComponent.isStorageFull(stack);
    }

    public static boolean isByteFull(ItemStack stack) {
        return InternalStorageComponent.isByteFull(stack);
    }

    public static int getCurrentByteUsage(ItemStack stack) {
        return InternalStorageComponent.getCurrentByteUsage(stack);
    }

    public static int getCurrentByteUsage(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        return InternalStorageComponent.getCurrentByteUsage(stack, registries);
    }

    public static int getMaxStorageBytes() {
        return InternalStorageComponent.MAX_STORAGE_BYTES;
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert) {
        return InternalStorageComponent.canInsert(chestStack, itemToInsert);
    }

    public static boolean canInsert(ItemStack chestStack, ItemStack itemToInsert, net.minecraft.core.HolderLookup.Provider registries) {
        return InternalStorageComponent.canInsert(chestStack, itemToInsert, registries);
    }

    public static void clearStorage(ItemStack chestStack) {
        if (!isLivingChest(chestStack)) return;
        InternalStorageComponent.clearStorage(chestStack);
    }

    public static void dropAllItems(ItemStack chestStack, Player player) {
        if (!isLivingChest(chestStack)) return;

        List<ItemStack> items = InternalStorageComponent.getItems(chestStack);
        Level level = player.level();
        BlockPos dropPos = player.blockPosition();
        int droppedCount = 0;

        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                level.addFreshEntity(new ItemEntity(
                    level,
                    dropPos.getX() + 0.5,
                    dropPos.getY() + 0.5,
                    dropPos.getZ() + 0.5,
                    item.copy()));
                droppedCount += item.getCount();
            }
        }

        InternalStorageComponent.clearStorage(chestStack);

        if (droppedCount > 0) {
            LivingItemManager.LOGGER.info("活箱子取消活化：掉落 {} 个物品", droppedCount);
        }
    }

    public static ComponentState getStorageState(ItemStack stack) {
        net.minecraft.nbt.CompoundTag funcData = LivingItemManager.getFunctionData(stack, ID).copy();
        if (funcData == null || funcData.isEmpty()) {
            return new ComponentState();
        }
        net.minecraft.nbt.CompoundTag storageTag = funcData.getCompound(InternalStorageComponent.ID).copy();
        return ComponentState.fromNBT(storageTag);
    }

    public static void saveStorageState(ItemStack stack, ComponentState state) {
        net.minecraft.nbt.CompoundTag funcData = LivingItemManager.getFunctionData(stack, ID).copy();
        funcData.put(InternalStorageComponent.ID, state.toNBT());
        LivingItemManager.setFunctionData(stack, ID, funcData);
    }
}