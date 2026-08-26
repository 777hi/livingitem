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
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

public class LivingCopperFunction implements LivingItemFunction, HasContainerData, HasDirection {

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

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.copper.title")
            .withStyle(ChatFormatting.GOLD));

        LivingCopperSignalData sigData = LivingItemManager.getCopperSignal(stack);
        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.copper.signal"))
            .append(Component.literal(": " + sigData.signalStrength()))
            .withStyle(sigData.signalStrength() > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));

        tooltipAdder.accept(Component.translatable("tooltip.livingitem.copper.max_signal")
            .append(Component.literal(": " + ContainerRedstoneData.getSignalCap(stack.getCount())))
            .withStyle(ChatFormatting.GRAY));

        if (isChiseled(item)) {
            LivingCutCopperData data = LivingItemManager.getCutCopperData(stack);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.copper.input_dir"))
                .append(Component.literal(": " + data.inputDir().getSymbol()))
                .withStyle(ChatFormatting.GREEN));
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.copper.output_dir"))
                .append(Component.literal(": " + data.outputDir().getSymbol()))
                .withStyle(ChatFormatting.GREEN));
        }

        if (isBulb(item)) {
            LivingCopperBulbData bulbData = LivingItemManager.getCopperBulbData(stack);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.copper.bulb_recorded"))
                .append(Component.literal(": " + bulbData.recordedSignal()))
                .withStyle(bulbData.recordedSignal() > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public int getComparatorOutput(ItemStack stack) {
        if (isBulb(stack.getItem())) {
            LivingCopperBulbData data = LivingItemManager.getCopperBulbData(stack);
            return data.recordedSignal();
        }
        return 0;
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
        if (isBulb(item)) return "Signal Memory";
        return "Copper";
    }

    private static final String[] CHISELED_SLOT_NAMES = {"input", "output"};

    @Override
    public int getDirectionKeyCount() {
        return 2;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return CHISELED_SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        LivingCutCopperData data = LivingItemManager.getCutCopperData(stack);
        switch (slotName) {
            case "input":
                LivingItemManager.setCutCopperData(stack, data.withInputDir(direction));
                return true;
            case "output":
                LivingItemManager.setCutCopperData(stack, data.withOutputDir(direction));
                return true;
            default:
                return false;
        }
    }
}