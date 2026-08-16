package com.qiqi.li.living.domain.redstone;

import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

public class LivingRedstoneTorchFunction implements LivingItemFunction, HasDirection, HasContainerData {

    public static final String ID = "living_redstone_torch";
    private static final String[] SLOT_NAMES = {"direction"};

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.REDSTONE_TORCH) && LivingItemManager.isLivingItem(stack);
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
    public int getDirectionKeyCount() {
        return 1;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        return updateTorchDirection(stack, direction);
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

    public static Pos2D getInputDirection(Pos2D facing) {
        if (facing.equals(Pos2D.UP)) return Pos2D.DOWN;
        if (facing.equals(Pos2D.DOWN)) return Pos2D.UP;
        if (facing.equals(Pos2D.LEFT)) return Pos2D.RIGHT;
        if (facing.equals(Pos2D.RIGHT)) return Pos2D.LEFT;
        return Pos2D.DOWN;
    }

    public static boolean updateTorchDirection(ItemStack torchStack, Pos2D direction) {
        if (torchStack == null || torchStack.isEmpty() || direction == null) return false;
        LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(torchStack);
        LivingItemManager.setRedstoneTorchData(torchStack, data.withDirection(direction));
        return true;
    }
}