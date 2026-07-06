package com.qiqi.li.living.core.components;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

public class FuelConsumeComponent implements ILivingComponent {

    public static final String ID = "fuel";
    private static final String KEY_BURN_TIME = "burn_time";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {

        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);
        int burnTime = state.getInt(KEY_BURN_TIME, 0);

        if (burnTime > 0) {
            burnTime--;
            state.setInt(KEY_BURN_TIME, burnTime);
            return;
        }

        if (ctx.hasValidFuel()) {
            ItemStack fuelStack = ctx.containerCtx().getItem(ctx.fuelSlot());
            int fuelValue = getFuelValue(fuelStack, recipeType);

            if (fuelValue > 0) {
                fuelStack.shrink(1);
                ctx.containerCtx().setItem(ctx.fuelSlot(), fuelStack.copy());
                burnTime = fuelValue;
                state.setInt(KEY_BURN_TIME, burnTime);
            }
        }
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        int burnTime = state.getInt(KEY_BURN_TIME, 0);
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.fuel_burn",
            String.format("%.1f", burnTime / 20.0)
        ));
    }

    public boolean isBurning(ComponentState state) {
        return state.getInt(KEY_BURN_TIME, 0) > 0;
    }

    public void pauseTick(ComponentState state) {
        int burnTime = state.getInt(KEY_BURN_TIME, 0);
        if (burnTime > 0) {
            state.setInt(KEY_BURN_TIME, Math.max(0, burnTime - 1));
        }
    }

    public boolean hasUsableFuel(ComponentContext ctx, ComponentConfig config) {
        if (!ctx.hasValidFuel()) {
            return false;
        }

        RecipeType<?> recipeType = config.get("recipe_type", RecipeType.class, RecipeType.SMELTING);
        ItemStack fuelStack = ctx.containerCtx().getItem(ctx.fuelSlot());
        int fuelValue = getFuelValue(fuelStack, recipeType);

        return fuelValue > 0;
    }

    private int getFuelValue(ItemStack stack, RecipeType<?> recipeType) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (!(item instanceof IItemExtension extension)) return 0;
        return extension.getBurnTime(stack, recipeType);
    }
}