package com.qiqi.li.living;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.qiqi.li.living.core.Direction2D;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.*;

public class LivingFurnaceFunction implements LivingItemFunction {

    public static final String ID = "living_furnace";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final LivingFunctionConfig CONFIG = createConfig();

    private static LivingFunctionConfig createConfig() {
        return new LivingFunctionConfig()
            .withFunctionId(ID)
            .withInput(Direction2D.LEFT)
            .withFuel(Direction2D.DOWN)
            .withOutput(Direction2D.RIGHT)
            .withStackMultiplier(true)
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
    public void addToTooltip(CompoundTag functionData, Item.TooltipContext context,
                             Consumer<Component> tooltipAdder, TooltipFlag flag) {
        if (functionData == null || functionData.isEmpty()) return;

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.furnace.status"));

        for (var componentEntry : CONFIG.getComponents()) {
            ILivingComponent component = FunctionExecutor.INSTANCE.getComponent(componentEntry.componentClass());
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
}