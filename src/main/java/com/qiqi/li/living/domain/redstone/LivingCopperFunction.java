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

public class LivingCopperFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_copper";

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return isUnwaxedCopperBlock(stack.getItem());
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
        var item = stack.getItem();
        int oxidation = getOxidationLevel(item);
        String typeName = getTypeName(item);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.copper.title")
            .withStyle(ChatFormatting.GOLD));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.copper.type"))
            .append(Component.literal(": " + typeName))
            .withStyle(ChatFormatting.YELLOW));

        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.copper.channel"))
            .append(Component.literal(": " + (char)('A' + oxidation)))
            .withStyle(ChatFormatting.GRAY));

        if (isChiseled(item)) {
            LivingCutCopperData data = LivingItemManager.getCutCopperData(stack);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.copper.direction"))
                .append(Component.literal(": " + data.direction().getSymbol()))
                .withStyle(ChatFormatting.GREEN));
        }
        if (isCut(item)) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.copper.chiseled_hint")
                .withStyle(ChatFormatting.AQUA));
        }
        if (isGrate(item)) {
            LivingGrateData data = LivingItemManager.getGrateData(stack);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.copper.grate_output"))
                .append(Component.literal(": " + data.output()))
                .withStyle(data.output() ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
        }
        if (isBulb(item)) {
            LivingCopperBulbData data = LivingItemManager.getCopperBulbData(stack);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.copper.bulb_state"))
                .append(Component.literal(": " + (data.lit() ? "ON" : "OFF")))
                .withStyle(data.lit() ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
        }
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

    // ── 静态工具方法 ──

    public static boolean isUnwaxedCopperBlock(Item item) {
        return item == Items.COPPER_BLOCK || item == Items.EXPOSED_COPPER
            || item == Items.WEATHERED_COPPER || item == Items.OXIDIZED_COPPER
            || item == Items.CHISELED_COPPER || item == Items.EXPOSED_CHISELED_COPPER
            || item == Items.WEATHERED_CHISELED_COPPER || item == Items.OXIDIZED_CHISELED_COPPER
            || item == Items.CUT_COPPER || item == Items.EXPOSED_CUT_COPPER
            || item == Items.WEATHERED_CUT_COPPER || item == Items.OXIDIZED_CUT_COPPER
            || item == Items.COPPER_GRATE || item == Items.EXPOSED_COPPER_GRATE
            || item == Items.WEATHERED_COPPER_GRATE || item == Items.OXIDIZED_COPPER_GRATE
            || item == Items.COPPER_BULB || item == Items.EXPOSED_COPPER_BULB
            || item == Items.WEATHERED_COPPER_BULB || item == Items.OXIDIZED_COPPER_BULB;
    }

    public static boolean isBaseCopper(Item item) {
        return item == Items.COPPER_BLOCK || item == Items.EXPOSED_COPPER
            || item == Items.WEATHERED_COPPER || item == Items.OXIDIZED_COPPER;
    }

    public static boolean isChiseled(Item item) {
        return item == Items.CHISELED_COPPER || item == Items.EXPOSED_CHISELED_COPPER
            || item == Items.WEATHERED_CHISELED_COPPER || item == Items.OXIDIZED_CHISELED_COPPER;
    }

    public static boolean isCut(Item item) {
        return item == Items.CUT_COPPER || item == Items.EXPOSED_CUT_COPPER
            || item == Items.WEATHERED_CUT_COPPER || item == Items.OXIDIZED_CUT_COPPER;
    }

    public static boolean isGrate(Item item) {
        return item == Items.COPPER_GRATE || item == Items.EXPOSED_COPPER_GRATE
            || item == Items.WEATHERED_COPPER_GRATE || item == Items.OXIDIZED_COPPER_GRATE;
    }

    public static boolean isBulb(Item item) {
        return item == Items.COPPER_BULB || item == Items.EXPOSED_COPPER_BULB
            || item == Items.WEATHERED_COPPER_BULB || item == Items.OXIDIZED_COPPER_BULB;
    }

    public static int getOxidationLevel(Item item) {
        if (item == Items.COPPER_BLOCK || item == Items.CHISELED_COPPER
            || item == Items.CUT_COPPER || item == Items.COPPER_GRATE
            || item == Items.COPPER_BULB) {
            return 0;
        }
        if (item == Items.EXPOSED_COPPER || item == Items.EXPOSED_CHISELED_COPPER
            || item == Items.EXPOSED_CUT_COPPER || item == Items.EXPOSED_COPPER_GRATE
            || item == Items.EXPOSED_COPPER_BULB) {
            return 1;
        }
        if (item == Items.WEATHERED_COPPER || item == Items.WEATHERED_CHISELED_COPPER
            || item == Items.WEATHERED_CUT_COPPER || item == Items.WEATHERED_COPPER_GRATE
            || item == Items.WEATHERED_COPPER_BULB) {
            return 2;
        }
        return 3;
    }

    private static String getTypeName(Item item) {
        if (isBaseCopper(item)) return "Cable";
        if (isChiseled(item)) return "Diode";
        if (isCut(item)) return "Overpass";
        if (isGrate(item)) return "Divider";
        if (isBulb(item)) return "T Flip-Flop";
        return "Copper";
    }
}