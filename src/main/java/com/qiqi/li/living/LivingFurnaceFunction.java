package com.qiqi.li.living;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class LivingFurnaceFunction implements LivingItemFunction {
    public static final String ID = "living_furnace";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAG_BURN_TIME = "burn_time";
    private static final String TAG_COOK_TIME = "cook_time";
    private static final String TAG_COOK_TIME_TOTAL = "cook_time_total";

    private static final int CONTAINER_WIDTH = 9;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public void tick(ItemStack stack, int slotIndex, Container container, Level level) {
        if (level.isClientSide) {
            return;
        }

        int containerSize = container.getContainerSize();
        int furnaceCount = Math.max(1, stack.getCount());

        int inputIndex = clampIndex(getRelativeIndex(slotIndex, CONTAINER_WIDTH, -1, 0), containerSize);
        int fuelIndex = clampIndex(getRelativeIndex(slotIndex, CONTAINER_WIDTH, 0, 1), containerSize);
        int outputIndex = clampIndex(getRelativeIndex(slotIndex, CONTAINER_WIDTH, 1, 0), containerSize);

        ItemStack inputStack = container.getItem(inputIndex);
        ItemStack fuelStack = container.getItem(fuelIndex);
        ItemStack outputStack = container.getItem(outputIndex);

        CompoundTag tag = LivingDataManager.getOrCreateContainerItemData(container, slotIndex, ID, level);

        int burnTime = tag.getInt(TAG_BURN_TIME);
        int cookTime = tag.getInt(TAG_COOK_TIME);
        int cookTimeTotal = tag.getInt(TAG_COOK_TIME_TOTAL);

        if (burnTime > 0) {
            burnTime--;
        }

        if (!inputStack.isEmpty()) {
            SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);
            var recipeHolderOpt = level.getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, recipeInput, level);

            if (recipeHolderOpt.isPresent()) {
                SmeltingRecipe recipe = recipeHolderOpt.get().value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                int resultCount = result.getCount();

                int outputSpace = getOutputSpace(outputStack, result);
                int maxByOutput = resultCount > 0 ? outputSpace / resultCount : 0;
                int smeltCount = Math.min(furnaceCount, Math.min(inputStack.getCount(), maxByOutput));

                if (smeltCount > 0) {
                    if (burnTime <= 0 && !fuelStack.isEmpty()) {
                        int fuelValue = getFuelValue(fuelStack);
                        if (fuelValue > 0) {
                            burnTime = fuelValue;
                            fuelStack.shrink(1);
                            container.setItem(fuelIndex, fuelStack.copy());
                        }
                    }

                    if (burnTime > 0) {
                        if (cookTimeTotal == 0) {
                            cookTimeTotal = recipe.getCookingTime();
                        }

                        cookTime++;

                        if (cookTime >= cookTimeTotal) {
                            cookTime = 0;
                            cookTimeTotal = 0;

                            inputStack.shrink(smeltCount);
                            container.setItem(inputIndex, inputStack.copy());

                            if (outputStack.isEmpty()) {
                                ItemStack newOutput = result.copy();
                                newOutput.setCount(smeltCount * resultCount);
                                container.setItem(outputIndex, newOutput);
                            } else {
                                outputStack.grow(smeltCount * resultCount);
                                container.setItem(outputIndex, outputStack.copy());
                            }
                        }
                    }
                } else {
                    cookTime = 0;
                    cookTimeTotal = 0;
                }
            } else {
                cookTime = 0;
                cookTimeTotal = 0;
            }
        } else {
            cookTime = 0;
            cookTimeTotal = 0;
        }

        tag.putInt(TAG_BURN_TIME, burnTime);
        tag.putInt(TAG_COOK_TIME, cookTime);
        tag.putInt(TAG_COOK_TIME_TOTAL, cookTimeTotal);
        LivingDataManager.setContainerItemData(container, slotIndex, ID, tag, level);
    }

    private int getRelativeIndex(int fromIndex, int width, int dx, int dy) {
        int row = fromIndex / width;
        int col = fromIndex % width;

        int newCol = col + dx;
        int newRow = row + dy;

        return newRow * width + newCol;
    }

    private int clampIndex(int index, int maxSize) {
        if (index < 0) return 0;
        if (index >= maxSize) return maxSize - 1;
        return index;
    }

    private int getOutputSpace(ItemStack outputStack, ItemStack result) {
        if (outputStack.isEmpty()) {
            return result.getMaxStackSize();
        }
        if (ItemStack.isSameItemSameComponents(outputStack, result)) {
            return outputStack.getMaxStackSize() - outputStack.getCount();
        }
        return 0;
    }

    private int getFuelValue(ItemStack stack) {
        return ((IItemExtension) stack.getItem()).getBurnTime(stack, RecipeType.SMELTING);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }
}