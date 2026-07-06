package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

public class ItemTransformComponent implements ILivingComponent {

    public static final String ID = "transform";
    private static final String KEY_LAST_TRANSFORM_TICK = "last_transform_tick";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        long lastTransformTick = state.getInt(KEY_LAST_TRANSFORM_TICK, -1);
        if (lastTransformTick == -1) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.transform.ready"));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.transform.processing"));
        }
    }

    public boolean executeTransform(ComponentContext ctx, ItemStack hostStack,
                                     ComponentConfig config, ProgressComponent progress,
                                     ComponentState transformState) {

        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);

        if (!ctx.hasValidInput() || !ctx.hasValidOutput()) return false;

        ItemStack inputStack = ctx.containerCtx().getItem(ctx.inputSlot());
        SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);

        var recipeHolderOpt = ctx.level().getRecipeManager()
                .getRecipeFor((RecipeType)recipeType, recipeInput, ctx.level());

        if (recipeHolderOpt.isEmpty()) return false;

        Object recipeHolder = recipeHolderOpt.get();
        Recipe<?> recipe;
        if (recipeHolder instanceof net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
            recipe = holder.value();
        } else {
            recipe = (Recipe<?>)recipeHolder;
        }
        ItemStack result = recipe.getResultItem(ctx.level().registryAccess());
        int resultCount = result.getCount();

        int stackMultiplier = Math.max(1, hostStack.getCount());
        int outputSpace = calculateOutputSpace(ctx, result);
        int maxByOutput = resultCount > 0 ? outputSpace / resultCount : 0;
        int transformCount = Math.min(stackMultiplier, Math.min(inputStack.getCount(), maxByOutput));

        if (transformCount <= 0) return false;

        inputStack.shrink(transformCount);
        ctx.containerCtx().setItem(ctx.inputSlot(), inputStack.copy());

        ItemStack outputStack = ctx.containerCtx().getItem(ctx.outputSlot());
        if (outputStack.isEmpty()) {
            ItemStack newOutput = result.copy();
            newOutput.setCount(transformCount * resultCount);
            ctx.containerCtx().setItem(ctx.outputSlot(), newOutput);
        } else {
            outputStack.grow(transformCount * resultCount);
            ctx.containerCtx().setItem(ctx.outputSlot(), outputStack.copy());
        }

        transformState.setInt(KEY_LAST_TRANSFORM_TICK, (int)(System.currentTimeMillis() / 1000));

        return true;
    }

    private int calculateOutputSpace(ComponentContext ctx, ItemStack result) {
        ItemStack outputStack = ctx.containerCtx().getItem(ctx.outputSlot());
        int maxStack = ctx.containerCtx().getMaxStackSize();

        if (outputStack.isEmpty()) {
            return Math.min(maxStack, result.getMaxStackSize());
        }

        if (ItemStack.isSameItemSameComponents(outputStack, result)) {
            return Math.min(maxStack, outputStack.getMaxStackSize()) - outputStack.getCount();
        }

        return 0;
    }

    public boolean canProcess(ComponentContext ctx) {
        if (!ctx.hasValidInput() || !ctx.hasValidOutput()) {
            return false;
        }

        RecipeType<?> recipeType = RecipeType.SMELTING;
        ItemStack inputStack = ctx.containerCtx().getItem(ctx.inputSlot());

        if (inputStack.isEmpty()) {
            return false;
        }

        SingleRecipeInput recipeInput = new SingleRecipeInput(inputStack);
        var recipeHolderOpt = ctx.level().getRecipeManager()
                .getRecipeFor((RecipeType)recipeType, recipeInput, ctx.level());

        return recipeHolderOpt.isPresent();
    }
}