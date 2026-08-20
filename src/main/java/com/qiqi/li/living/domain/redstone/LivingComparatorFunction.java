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

public class LivingComparatorFunction implements LivingItemFunction, HasDirection, HasContainerData {

    public static final String ID = "living_comparator";
    private static final String[] SLOT_NAMES = {"direction"};

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.COMPARATOR) && LivingItemManager.isLivingItem(stack);
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
        LivingComparatorData data = LivingItemManager.getComparatorData(stack);
        int signalCap = ContainerRedstoneData.getSignalCap(stack.getCount());

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.comparator.title"));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.comparator.facing"))
            .append(Component.literal(": "))
            .append(Component.translatable("tooltip.livingitem.comparator.direction." + directionKey(data.direction())))
            .withStyle(ChatFormatting.GRAY));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.comparator.mode"))
            .append(Component.literal(": "))
            .append(Component.translatable("tooltip.livingitem.comparator.mode." + (data.subtractMode() ? "subtract" : "compare")))
            .withStyle(ChatFormatting.GRAY));

        if (data.powered()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.comparator.powered")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.comparator.unpowered")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.comparator.max_signal")
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
        return updateComparatorDirection(stack, direction);
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

    public static boolean updateComparatorDirection(ItemStack stack, Pos2D direction) {
        if (stack == null || stack.isEmpty() || direction == null) return false;
        LivingComparatorData data = LivingItemManager.getComparatorData(stack);
        LivingItemManager.setComparatorData(stack, data.withDirection(direction));
        return true;
    }

    public static boolean toggleMode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        LivingComparatorData data = LivingItemManager.getComparatorData(stack);
        LivingItemManager.setComparatorData(stack, data.withSubtractMode(!data.subtractMode()));
        return true;
    }

    public static int readComparatorOutput(ItemStack stack, int maxSignal) {
        if (stack.isEmpty()) return 0;

        if (LivingItemManager.isLivingItem(stack)) {
            for (LivingItemFunction func : LivingItemManager.getApplicableFunctions(stack)) {
                int output = func.getComparatorOutput(stack);
                if (output > 0) return Math.min(output, maxSignal);
            }
            return 0;
        }
        int maxStackSize = stack.getMaxStackSize();
        return maxStackSize > 0 ? Math.max(1, Math.round((float) stack.getCount() / maxStackSize * maxSignal)) : 0;
    }
}