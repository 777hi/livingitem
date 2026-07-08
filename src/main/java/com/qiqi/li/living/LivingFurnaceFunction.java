package com.qiqi.li.living;

import java.util.List;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.components.*;

public class LivingFurnaceFunction implements LivingItemFunction {

    public static final String ID = "living_furnace";

    private static final LivingFunctionConfig CONFIG = createConfig();

    private static LivingFunctionConfig createConfig() {
        return new LivingFunctionConfig()
            .withFunctionId(ID)
            .withStackMultiplier(true)
            .addComponent(new DirectionModeComponent(Map.of(
                "input", Pos2D.LEFT,
                "fuel", Pos2D.DOWN,
                "output", Pos2D.RIGHT
            )))
            .addComponent(FuelConsumeComponent.class,
                ComponentConfig.of("recipe_type", RecipeType.SMELTING))
            .addComponent(ProgressComponent.class,
                ComponentConfig.of("total_ticks", 200))
            .addComponent(ItemTransformComponent.class,
                ComponentConfig.of("recipe_type", RecipeType.SMELTING));
    }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.FURNACE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, Level level) {
        if (level.isClientSide) return;
        if (entries.isEmpty()) return;

        for (SlotEntry entry : entries) {
            FunctionExecutor.INSTANCE.tick(context, entry.slotIndex(), entry.stack(), CONFIG, level);
        }
    }

    @Override
    public void addToTooltip(net.minecraft.nbt.CompoundTag functionData,
                             net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag) {

        if (functionData == null || functionData.isEmpty()) return;

        tooltipAdder.accept(net.minecraft.network.chat.Component.nullToEmpty(""));
        tooltipAdder.accept(net.minecraft.network.chat.Component.translatable("tooltip.livingitem.furnace.status"));

        for (var componentEntry : CONFIG.getComponents()) {
            ILivingComponent component = FunctionExecutor.INSTANCE.resolveComponent(
                CONFIG, componentEntry);
            String compId = component.getComponentId();

            if (functionData.contains(compId)) {
                ComponentState state = ComponentState.fromNBT(functionData.getCompound(compId));
                component.appendTooltip(state, tooltipAdder);
            }
        }
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    public static LivingFunctionConfig getConfig() {
        return CONFIG;
    }
}