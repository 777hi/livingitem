package com.qiqi.li.living.domain.water;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.water.LivingWaterWheelData;
import com.qiqi.li.living.domain.water.WaterWheelData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class LivingWaterWheelFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_water_wheel";

    private static Item WATER_WHEEL_ITEM;

    private static Item getWaterWheelItem() {
        if (WATER_WHEEL_ITEM == null) {
            WATER_WHEEL_ITEM = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "water_wheel"));
        }
        return WATER_WHEEL_ITEM;
    }

    private static boolean isWaterWheelItem(ItemStack stack) {
        Item item = getWaterWheelItem();
        return item != null && stack.is(item);
    }

    @Override
    public boolean canApply(ItemStack stack) {
        return isWaterWheelItem(stack) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingWaterWheelData data = LivingItemManager.getWaterWheelData(stack);
        WaterWheelData wheel = data.wheel();

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_wheel.status"));

        if (wheel.cwStress() == 0 && wheel.ccwStress() == 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_wheel.no_stress"));
            return;
        }

        if (wheel.netStress() > 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_wheel.stress",
                wheel.cwStress(), wheel.ccwStress(), wheel.netStress()).withStyle(net.minecraft.ChatFormatting.GOLD));
        } else if (wheel.netStress() < 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_wheel.stress",
                wheel.cwStress(), wheel.ccwStress(), wheel.netStress()).withStyle(net.minecraft.ChatFormatting.AQUA));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_wheel.balanced",
                wheel.cwStress(), wheel.ccwStress()).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of(LivingItemManager.LIVING_WATER_WHEEL_DATA.value());
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerFluidData fluidData = tick.fluidData;
        ContainerStressData stressData = tick.stressData;
        if (stressData != null && fluidData != null && !fluidData.isEmpty()) {
            Set<Integer> waterWheelSlots = new HashSet<>();
            for (var entry : entries) {
                waterWheelSlots.add(entry.slotIndex());
            }
            stressData.calculate(fluidData, ctx, waterWheelSlots);
        }
        postTickSync(ctx, stressData, entries);
    }

    public static boolean isLivingWaterWheel(ItemStack stack) {
        return isWaterWheelItem(stack) && LivingItemManager.isLivingItem(stack);
    }

    public static void postTickSync(ContainerContext ctx, ContainerStressData stressData,
        List<SlotEntry> waterWheelEntries) {
        if (waterWheelEntries.isEmpty() || stressData == null) return;

        var torques = stressData.getWheelTorques();

        for (SlotEntry entry : waterWheelEntries) {
            int i = entry.slotIndex();
            ItemStack stack = ctx.getItem(i);
            if (!isLivingWaterWheel(stack)) continue;

            int[] torque = torques.get(i);
            int cw = torque != null ? torque[0] : 0;
            int ccw = torque != null ? torque[1] : 0;
            int net = cw - ccw;

            LivingWaterWheelData data = LivingItemManager.getWaterWheelData(stack);
            WaterWheelData current = data.wheel();

            if (current.cwStress() != cw || current.ccwStress() != ccw || current.netStress() != net) {
                WaterWheelData updated = current.withStress(cw, ccw, net);
                LivingItemManager.setWaterWheelData(stack, data.withWheel(updated));
                ctx.syncSlotToClients(i, stack);
            }
        }
    }
}