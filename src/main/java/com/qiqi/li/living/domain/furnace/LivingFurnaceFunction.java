package com.qiqi.li.living.domain.furnace;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.transfer.SlotResolver;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.domain.furnace.DirectionSlotsData;
import com.qiqi.li.living.domain.furnace.FuelData;
import com.qiqi.li.living.domain.furnace.LivingFurnaceData;
import com.qiqi.li.living.domain.furnace.ProgressData;
import com.qiqi.li.living.domain.furnace.TransformData;

public class LivingFurnaceFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_furnace";
    private static final int DEFAULT_COOKING_TIME = 200;
    private static final String[] SLOT_NAMES = {"input", "output", "fuel"};

    public static final DirectionSlotsData DEFAULT_DIRECTION = DirectionSlotsData.DEFAULT_FURNACE;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= context.getSize()) continue;

            ItemStack stack = entry.stack();
            LivingFurnaceData data = LivingItemManager.getFurnaceData(stack);

            DirectionSlotsData dir = data.direction();
            if (dir.directions().isEmpty()) {
                dir = DEFAULT_DIRECTION;
                data = data.withDirection(dir);
            }
            int containerSize = context.getSize();
            int containerWidth = context.getWidth();

            int inputSlot = SlotResolver.resolve(slot, dir.getDirection("input"), containerSize, containerWidth);
            int fuelSlot = SlotResolver.resolve(slot, dir.getDirection("fuel"), containerSize, containerWidth);
            int outputSlot = SlotResolver.resolve(slot, dir.getDirection("output"), containerSize, containerWidth);

            data = tickTransform(context, data, inputSlot, level);

            boolean canProgress = checkCanProgress(context, level, inputSlot, fuelSlot, outputSlot, data);

            if (canProgress) {
                int step = 1 + stack.getCount() / 8;
                data = data.withProgress(data.progress().advanceBy(step));
                data = tickFuel(context, data, fuelSlot, stack.getCount());

                if (data.progress().isComplete() && data.fuel().isBurning()) {
                    boolean success = executeTransform(context, level, data, inputSlot, outputSlot, stack.getCount());
                    if (success) {
                        data = data.withProgress(data.progress().reset());
                    }
                }
            } else {
                data = pauseTick(data);
            }

            LivingItemManager.setFurnaceData(stack, data);
            context.syncSlotToClients(slot, stack);
        }
    }

    private boolean checkCanProgress(ContainerContext ctx, Level level,
                                      int inputSlot, int fuelSlot, int outputSlot,
                                      LivingFurnaceData data) {
        if (inputSlot < 0 || outputSlot < 0) return false;

        ItemStack inputStack = ctx.getItem(inputSlot);
        if (inputStack.isEmpty() || LivingItemManager.isLivingItem(inputStack)) return false;

        if (!data.fuel().isBurning()) {
            if (fuelSlot < 0) return false;
            ItemStack fuelStack = ctx.getItem(fuelSlot);
            if (fuelStack.isEmpty()) return false;
            int fuelValue = getFuelValue(fuelStack);
            if (fuelValue <= 0 || LivingItemManager.isLivingItem(fuelStack)) return false;
        }

        if (!hasMatchingRecipe(level, inputStack, data.transform())) return false;

        String outputItemId = data.transform().cachedOutput();
        if (!outputItemId.isEmpty()) {
            ItemStack outputStack = ctx.getItem(outputSlot);
            String outputKey = outputStack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(outputStack.getItem()).toString();
            if (!data.transform().canAcceptOutput(outputKey, outputStack.getCount(),
                ctx.getSlotLimit(outputSlot), outputStack.getMaxStackSize())) {
                return false;
            }
        }

        return true;
    }

    private LivingFurnaceData tickFuel(ContainerContext ctx, LivingFurnaceData data, int fuelSlot, int stackCount) {
        FuelData fuel = data.fuel();
        if (fuel.isBurning()) {
            return data.withFuel(fuel.tick(Math.max(1, stackCount)));
        }

        if (fuelSlot < 0) return data;
        ItemStack fuelStack = ctx.getItem(fuelSlot);
        int fuelValue = getFuelValue(fuelStack);
        if (fuelValue > 0 && !LivingItemManager.isLivingItem(fuelStack)) {
            fuelStack.shrink(1);
            ctx.setItem(fuelSlot, fuelStack.copy());
            return data.withFuel(new FuelData(fuelValue));
        }

        return data;
    }

    private LivingFurnaceData tickTransform(ContainerContext ctx, LivingFurnaceData data, int inputSlot, Level level) {
        if (inputSlot < 0) return data;
        ItemStack inputStack = ctx.getItem(inputSlot);
        if (inputStack.isEmpty() || LivingItemManager.isLivingItem(inputStack)) {
            return data.withTransform(TransformData.EMPTY);
        }

        String inputKey = BuiltInRegistries.ITEM.getKey(inputStack.getItem()).toString();
        TransformData transform = data.transform();

        if (inputKey.equals(transform.cachedInput()) && transform.cachedResult() == 1) {
            if (transform.inputItem().isEmpty()) {
                transform = transform.withInputItem(inputKey);
                data = data.withTransform(transform);
            }
            return data;
        }

        var recipeHolderOpt = level.getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(inputStack), level);
        if (recipeHolderOpt.isPresent()) {
            Recipe<?> recipe = unwrapRecipe(recipeHolderOpt.get());
            ItemStack result = recipe.getResultItem(level.registryAccess());
            String outputKey = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            int cookingTime = DEFAULT_COOKING_TIME;

            transform = transform.withInputItem(inputKey)
                .withOutputItem(outputKey)
                .withCache(inputKey, 1, outputKey, result.getCount(), cookingTime);
            data = data.withTransform(transform);
        } else {
            if (!transform.inputItem().isEmpty()) {
                transform = transform.withInputItem(inputKey).withOutputItem("");
                transform = transform.withCache(inputKey, 0, "", 0, 0);
                data = data.withTransform(transform);
            }
        }

        return data;
    }

    private LivingFurnaceData pauseTick(LivingFurnaceData data) {
        ProgressData progress = data.progress();
        if (progress.progress() > 0) {
            data = data.withProgress(progress.recede());
        }
        FuelData fuel = data.fuel();
        if (fuel.isBurning()) {
            data = data.withFuel(fuel.tick(1));
        }
        return data;
    }

    private boolean executeTransform(ContainerContext ctx, Level level, LivingFurnaceData data,
                                      int inputSlot, int outputSlot, int stackCount) {
        if (inputSlot < 0 || outputSlot < 0) return false;

        ItemStack inputStack = ctx.getItem(inputSlot);
        if (inputStack.isEmpty() || LivingItemManager.isLivingItem(inputStack)) return false;

        var recipeHolderOpt = level.getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(inputStack), level);
        if (recipeHolderOpt.isEmpty()) return false;

        Recipe<?> recipe = unwrapRecipe(recipeHolderOpt.get());
        ItemStack result = recipe.getResultItem(level.registryAccess());
        int resultCount = result.getCount();

        int outputSpace = calculateOutputSpace(ctx, outputSlot, result);
        int maxByOutput = resultCount > 0 ? outputSpace / resultCount : 0;
        int transformCount = Math.min(stackCount, Math.min(inputStack.getCount(), maxByOutput));
        if (transformCount <= 0) return false;

        inputStack.shrink(transformCount);
        ctx.setItem(inputSlot, inputStack.copy());

        ItemStack outputStack = ctx.getItem(outputSlot);
        if (outputStack.isEmpty()) {
            ItemStack newOutput = result.copy();
            newOutput.setCount(transformCount * resultCount);
            ctx.setItem(outputSlot, newOutput);
        } else {
            outputStack.grow(transformCount * resultCount);
            ctx.setItem(outputSlot, outputStack.copy());
        }

        return true;
    }

    private int calculateOutputSpace(ContainerContext ctx, int outputSlot, ItemStack result) {
        ItemStack outputStack = ctx.getItem(outputSlot);
        int slotLimit = ctx.getSlotLimit(outputSlot);
        if (outputStack.isEmpty()) {
            return Math.min(slotLimit, result.getMaxStackSize());
        }
        if (ItemStack.isSameItemSameComponents(outputStack, result)) {
            int maxCount = Math.min(slotLimit, outputStack.getMaxStackSize());
            return maxCount - outputStack.getCount();
        }
        return 0;
    }

    private boolean hasMatchingRecipe(Level level, ItemStack input, TransformData transform) {
        String inputKey = BuiltInRegistries.ITEM.getKey(input.getItem()).toString();
        if (inputKey.equals(transform.cachedInput()) && transform.cachedResult() == 1) {
            return true;
        }
        if (inputKey.equals(transform.cachedInput()) && transform.cachedResult() == 0) {
            return false;
        }
        var opt = level.getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);
        return opt.isPresent();
    }

    private static Recipe<?> unwrapRecipe(Object recipeHolder) {
        if (recipeHolder instanceof net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
            return holder.value();
        }
        return (Recipe<?>) recipeHolder;
    }

    private static int getFuelValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (!(item instanceof IItemExtension extension)) return 0;
        return extension.getBurnTime(stack, RecipeType.SMELTING);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingFurnaceData data = LivingItemManager.getFurnaceData(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.furnace.status"));

        FuelData fuel = data.fuel();
        if (fuel.isBurning()) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.fuel_burn",
                String.format("%.1f", fuel.burnTime() / 20.0)));
        } else if (fuel.burnTime() > 0) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.fuel_remaining",
                String.format("%.1f", fuel.burnTime() / 20.0)));
        }

        ProgressData progress = data.progress();
        if (progress.total() > 0) {
            int percent = (int) ((progress.progress() * 100.0f) / progress.total());
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.progress",
                percent,
                String.format("%.1f", progress.progress() / 20.0),
                String.format("%.1f", progress.total() / 20.0)));
        }

        TransformData transform = data.transform();
        if (!transform.inputItem().isEmpty() && !transform.outputItem().isEmpty()) {
            Component inputName = getItemDisplayName(transform.inputItem());
            Component outputName = getItemDisplayName(transform.outputItem());
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.transform.recipe", inputName, outputName));
        } else if (!transform.inputItem().isEmpty()) {
            Component inputName = getItemDisplayName(transform.inputItem());
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.transform.no_recipe", inputName));
        }

        DirectionSlotsData dir = data.direction();
        if (dir.directions().isEmpty()) {
            dir = DEFAULT_DIRECTION;
        }
        String[] slotOrder = {"input", "output", "fuel"};
        for (String slotName : slotOrder) {
            Pos2D d = dir.getDirection(slotName);
            if (d != null && d != Pos2D.NONE) {
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.direction.slot",
                    Component.translatable("slot.livingitem." + slotName),
                    d.getSymbol()
                ).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
    }

    private static net.minecraft.network.chat.Component getItemDisplayName(String itemId) {
        try {
            ResourceLocation rl = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) {
                return new ItemStack(item).getDisplayName();
            }
        } catch (Exception ignored) {
        }
        return net.minecraft.network.chat.Component.literal(itemId);
    }

    public static boolean isBurning(ItemStack stack) {
        return LivingItemManager.getFurnaceData(stack).fuel().isBurning();
    }

    public static DirectionSlotsData getDefaultDirection() {
        return DEFAULT_DIRECTION;
    }

    @Override
    public int getDirectionKeyCount() {
        return 3;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (stack == null || stack.isEmpty() || slotName == null || direction == null) return false;
        LivingFurnaceData data = LivingItemManager.getFurnaceData(stack);
        DirectionSlotsData dir = data.direction();
        dir = dir.withDirection(slotName, direction);
        LivingItemManager.setFurnaceData(stack, data.withDirection(dir));
        return true;
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}