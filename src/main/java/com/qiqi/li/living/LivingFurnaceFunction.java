package com.qiqi.li.living;

import net.minecraft.nbt.CompoundTag;
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

    private static final String DATA_BURN_TIME = "burn_time";
    private static final String DATA_COOK_TIME = "cook_time";
    private static final String DATA_COOK_TIME_TOTAL = "cook_time_total";
    private static final int CONTAINER_WIDTH = 9;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public void tick(ItemStack stack, int slotIndex, ContainerContext context, Level level) {
        if (level.isClientSide) return;

        int furnaceCount = Math.max(1, stack.getCount());

        int inputIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, -1, 0);
        int fuelIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, 0, 1);
        int outputIndex = getRelativeIndex(slotIndex, CONTAINER_WIDTH, 1, 0);

        if (!context.isValidSlot(inputIndex) || !context.isValidSlot(fuelIndex) || !context.isValidSlot(outputIndex)) {
            return;
        }

        ItemStack inputStack = context.getItem(inputIndex);
        ItemStack fuelStack = context.getItem(fuelIndex);
        ItemStack outputStack = context.getItem(outputIndex);

        // 从活熔炉物品自身的 NBT 读取进度（数据随物品持久化，不泄漏内存）
        CompoundTag tag = LivingItemManager.getFunctionData(stack, ID);
        int burnTime = tag.getInt(DATA_BURN_TIME);
        int cookTime = tag.getInt(DATA_COOK_TIME);
        int cookTimeTotal = tag.getInt(DATA_COOK_TIME_TOTAL);

        if (burnTime > 0) burnTime--;

        if (!inputStack.isEmpty()) {
            SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);
            var recipeHolderOpt = level.getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, recipeInput, level);

            if (recipeHolderOpt.isPresent()) {
                SmeltingRecipe recipe = recipeHolderOpt.get().value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                int resultCount = result.getCount();

                int outputSpace = getOutputSpace(outputStack, result, context.getMaxStackSize());
                int maxByOutput = resultCount > 0 ? outputSpace / resultCount : 0;
                int smeltCount = Math.min(furnaceCount, Math.min(inputStack.getCount(), maxByOutput));

                if (smeltCount > 0) {
                    if (burnTime <= 0 && !fuelStack.isEmpty()) {
                        int fuelValue = getFuelValue(fuelStack);
                        if (fuelValue > 0) {
                            burnTime = fuelValue;
                            fuelStack.shrink(1);
                            context.setItem(fuelIndex, fuelStack.copy());
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
                            context.setItem(inputIndex, inputStack.copy());

                            if (outputStack.isEmpty()) {
                                ItemStack newOutput = result.copy();
                                newOutput.setCount(smeltCount * resultCount);
                                context.setItem(outputIndex, newOutput);
                            } else {
                                outputStack.grow(smeltCount * resultCount);
                                context.setItem(outputIndex, outputStack.copy());
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

        // 写回活熔炉物品自身的 NBT（自动持久化到物品，重启不丢失）
        tag.putInt(DATA_BURN_TIME, burnTime);
        tag.putInt(DATA_COOK_TIME, cookTime);
        tag.putInt(DATA_COOK_TIME_TOTAL, cookTimeTotal);
        LivingItemManager.setFunctionData(stack, ID, tag);
    }

    private int getRelativeIndex(int fromIndex, int width, int dx, int dy) {
        int row = fromIndex / width;
        int col = fromIndex % width;
        return (row + dy) * width + (col + dx);
    }

    private int getOutputSpace(ItemStack outputStack, ItemStack result, int maxStackSize) {
        if (outputStack.isEmpty()) {
            return Math.min(maxStackSize, result.getMaxStackSize());
        }
        if (ItemStack.isSameItemSameComponents(outputStack, result)) {
            return Math.min(maxStackSize, outputStack.getMaxStackSize()) - outputStack.getCount();
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