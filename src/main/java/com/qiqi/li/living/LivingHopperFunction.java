package com.qiqi.li.living;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.core.FunctionExecutor;
import com.qiqi.li.living.core.LivingFunctionConfig;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.DirectionModeComponent;
import com.qiqi.li.living.core.components.ItemTransferComponent;
import com.qiqi.li.living.core.components.ILivingComponent;
import com.qiqi.li.living.core.model.SlotMapping;

public class LivingHopperFunction implements LivingItemFunction {

    public static final String ID = "living_hopper";

    private static final LivingFunctionConfig CONFIG = createConfig();

    private static LivingFunctionConfig createConfig() {
        return new LivingFunctionConfig()
            .withFunctionId(ID)
            .withStackMultiplier(true)
            .addComponent(new DirectionModeComponent())
            .addComponent(new ItemTransferComponent());
    }

    @Override
    public boolean canApply(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        Item item = stack.getItem();

        boolean isHopper = (item == Items.HOPPER) ||
                           ("minecraft:hopper".equals(BuiltInRegistries.ITEM.getKey(item).toString())) ||
                           (stack.is(Items.HOPPER));

        return isHopper && LivingItemManager.isLivingItem(stack);
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
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.hopper.status"));

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

    public static boolean updateTransferMapping(ItemStack hopperStack, SlotMapping newMapping) {
        if (hopperStack == null || hopperStack.isEmpty() || newMapping == null) {
            return false;
        }

        CompoundTag functionTag = LivingItemManager.getFunctionData(hopperStack, ID);

        ComponentState dirState;
        String dirCompId = DirectionModeComponent.ID;

        if (functionTag.contains(dirCompId)) {
            dirState = ComponentState.fromNBT(functionTag.getCompound(dirCompId));
        } else {
            DirectionModeComponent tempComp = new DirectionModeComponent();
            dirState = tempComp.createDefaultState();
        }

        DirectionModeComponent dirComp = new DirectionModeComponent();
        boolean success = dirComp.updateMapping(dirState, newMapping);

        if (!success) {
            return false;
        }

        functionTag.put(dirCompId, dirState.toNBT());
        LivingItemManager.setFunctionData(hopperStack, ID, functionTag);

        return true;
    }

    public static boolean updateTransferFromInput(ItemStack hopperStack, String rawInput) {
        if (hopperStack == null || hopperStack.isEmpty() || rawInput == null || rawInput.isEmpty()) {
            return false;
        }

        CompoundTag functionTag = LivingItemManager.getFunctionData(hopperStack, ID);

        ComponentState dirState;
        String dirCompId = DirectionModeComponent.ID;

        if (functionTag.contains(dirCompId)) {
            dirState = ComponentState.fromNBT(functionTag.getCompound(dirCompId));
        } else {
            DirectionModeComponent tempComp = new DirectionModeComponent();
            dirState = tempComp.createDefaultState();
        }

        DirectionModeComponent dirComp = new DirectionModeComponent();
        boolean success = dirComp.updateFromInput(dirState, rawInput);

        if (!success) {
            return false;
        }

        functionTag.put(dirCompId, dirState.toNBT());
        LivingItemManager.setFunctionData(hopperStack, ID, functionTag);

        return true;
    }
}