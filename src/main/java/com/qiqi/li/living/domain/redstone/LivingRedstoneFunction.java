package com.qiqi.li.living.domain.redstone;

import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public class LivingRedstoneFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_redstone";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.REDSTONE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerRedstoneData redstoneData = tick.redstoneData;
        if (redstoneData == null) {
            redstoneData = new ContainerRedstoneData(ctx.getSize());
            tick.redstoneData = redstoneData;
        }
        redstoneData.calculate(ctx, tick);
    }
}