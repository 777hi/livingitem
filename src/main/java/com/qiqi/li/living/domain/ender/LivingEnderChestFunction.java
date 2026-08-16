package com.qiqi.li.living.domain.ender;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.ender.EnderChannelClientCache;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.domain.ender.EnderChannelData;
import com.qiqi.li.living.domain.ender.LivingEnderChestData;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;

public class LivingEnderChestFunction implements LivingItemFunction {

    public static final String ID = "living_ender_chest";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        Set<Integer> activeEnderChestSlots = new HashSet<>();
        for (SlotEntry entry : entries) {
            activeEnderChestSlots.add(entry.slotIndex());
        }

        Set<Integer> activeHopperSlots = tick.getFunctionSlots(LivingHopperFunction.ID);
        EnderChannelRegistry.getInstance().validateRoutes(context, activeHopperSlots, activeEnderChestSlots);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
        EnderChannelData channel = data.channel();

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_chest.status"));

        if (channel.boundPlayerUuid().isPresent()) {
            String name = channel.boundPlayerName().orElse("???");
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.bound_player", name)
                .withStyle(style -> style.withColor(0xDD44FF).withBold(true)));
        } else {
            int ch = stack.getCount();
            var snapshot = EnderChannelClientCache.getSnapshot(ch);

            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.channel", ch)
                .withStyle(style -> style.withColor(0xCC66FF)));
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.routes", snapshot.channelSize(), snapshot.totalRoutes())
                .withStyle(style -> style.withColor(0xAA88FF)));

            if (snapshot.channelSize() > 0 && flag.isAdvanced()) {
                for (var entry : snapshot.entries()) {
                    String locStr;
                    if (entry.sourcePos() != null) {
                        locStr = entry.sourcePos().toShortString();
                    } else if (entry.dimKey() != null) {
                        locStr = entry.dimKey();
                    } else {
                        locStr = "???";
                    }
                    tooltipAdder.accept(Component.literal(
                        "  " + entry.itemType() + " @" + locStr + " slot=" + entry.sourceSlot())
                        .withStyle(style -> style.withColor(0x9966CC).withItalic(true)));
                }
            }
        }
    }

    public static boolean isLivingEnderChest(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean hasBoundPlayer(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return false;
        return LivingItemManager.getEnderChestData(stack).channel().boundPlayerUuid().isPresent();
    }

    public static UUID getBoundPlayerUuid(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        return LivingItemManager.getEnderChestData(stack).channel().getPlayerUuid().orElse(null);
    }

    public static String getBoundPlayerName(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        return LivingItemManager.getEnderChestData(stack).channel().boundPlayerName().orElse(null);
    }

    public static void setBoundPlayer(ItemStack stack, UUID uuid, String name) {
        LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
        EnderChannelData channel = data.channel().withBoundPlayer(uuid, name);
        LivingItemManager.setEnderChestData(stack, data.withChannel(channel));
    }

    public static void clearBoundPlayer(ItemStack stack) {
        LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
        LivingItemManager.setEnderChestData(stack, data.withChannel(EnderChannelData.EMPTY));
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}