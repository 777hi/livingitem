package com.qiqi.li.living.domain.redstone;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

public class LivingRepeaterFunction implements LivingItemFunction, HasDirection, HasContainerData {

    public static final String ID = "living_repeater";
    private static final String[] SLOT_NAMES = {"direction"};

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.REPEATER) && LivingItemManager.isLivingItem(stack);
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
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
        int signalCap = ContainerRedstoneData.getSignalCap(stack.getCount());

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.repeater.title"));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.repeater.facing"))
            .append(Component.literal(": "))
            .append(Component.translatable("tooltip.livingitem.repeater.direction." + directionKey(data.direction())))
            .withStyle(ChatFormatting.GRAY));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.repeater.delay"))
            .append(Component.literal(": " + data.delay() + " (" + (data.delay() * 2) + " ticks)"))
            .withStyle(ChatFormatting.GRAY));

        if (data.powered()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.repeater.powered")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            if (data.delayTimer() > 0) {
                tooltipAdder.accept(Component.translatable("tooltip.livingitem.repeater.delay_timer")
                    .append(Component.literal(": " + data.delayTimer()))
                    .withStyle(ChatFormatting.GRAY));
            }
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.repeater.unpowered")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.repeater.max_signal")
            .append(Component.literal(": " + signalCap))
            .withStyle(ChatFormatting.GRAY));
    }

    private static String directionKey(Pos2D dir) {
        if (dir.equals(Pos2D.UP)) return "up";
        if (dir.equals(Pos2D.DOWN)) return "down";
        if (dir.equals(Pos2D.LEFT)) return "left";
        if (dir.equals(Pos2D.RIGHT)) return "right";
        return "right";
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
        return updateRepeaterDirection(stack, direction);
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerRedstoneData redstoneData = tick.getOrCreateRedstoneData(ctx);
        redstoneData.calculate(ctx, tick);
    }

    public static boolean updateRepeaterDirection(ItemStack stack, Pos2D direction) {
        if (stack == null || stack.isEmpty() || direction == null) return false;
        LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
        LivingItemManager.setRepeaterData(stack, data.withDirection(direction));
        return true;
    }

    public static boolean cycleDelay(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
        int newDelay = data.delay() >= 4 ? 1 : data.delay() + 1;
        LivingItemManager.setRepeaterData(stack, data.withDelay(newDelay));
        return true;
    }
}