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
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public class LivingButtonFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_button";

    private static final Set<Item> WOOD_BUTTONS = Set.of(
        Items.OAK_BUTTON, Items.SPRUCE_BUTTON, Items.BIRCH_BUTTON,
        Items.JUNGLE_BUTTON, Items.ACACIA_BUTTON, Items.CHERRY_BUTTON,
        Items.DARK_OAK_BUTTON, Items.MANGROVE_BUTTON, Items.BAMBOO_BUTTON,
        Items.CRIMSON_BUTTON, Items.WARPED_BUTTON
    );

    private static final Set<Item> STONE_BUTTONS = Set.of(
        Items.STONE_BUTTON, Items.POLISHED_BLACKSTONE_BUTTON
    );

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return WOOD_BUTTONS.contains(stack.getItem()) || STONE_BUTTONS.contains(stack.getItem());
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
        LivingButtonData data = LivingItemManager.getButtonData(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.button.title"));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.button.type"))
            .append(Component.literal(": "))
            .append(Component.translatable("tooltip.livingitem.button.type." + (data.isWood() ? "wood" : "stone")))
            .withStyle(ChatFormatting.GRAY));

        if (data.pressed()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.button.pressed")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.button.pulse")
                .append(Component.literal(": " + data.pulseTimer() + " ticks"))
                .withStyle(ChatFormatting.GRAY));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.button.unpressed")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.button.max_signal")
            .append(Component.literal(": " + ContainerRedstoneData.getSignalCap(stack.getCount())))
            .withStyle(ChatFormatting.GRAY));
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

    public static boolean isWoodButton(ItemStack stack) {
        return WOOD_BUTTONS.contains(stack.getItem());
    }

    public static boolean pressButton(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        LivingButtonData data = LivingItemManager.getButtonData(stack);
        if (data.pressed()) return false;
        boolean isWood = isWoodButton(stack);
        int pulseDuration = isWood ? 30 : 20;
        LivingItemManager.setButtonData(stack, new LivingButtonData(true, pulseDuration, isWood));
        return true;
    }
}