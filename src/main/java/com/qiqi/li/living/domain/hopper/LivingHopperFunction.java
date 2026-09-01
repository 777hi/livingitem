package com.qiqi.li.living.domain.hopper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.qiqi.li.living.transfer.SlotResolver;
import com.qiqi.li.living.domain.ender.EnderChannelKey;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.ResolvedSlots;
import com.qiqi.li.living.model.SlotMapping;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.domain.hopper.DirectionTransferData;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.domain.hopper.LivingHopperData;
import com.qiqi.li.living.domain.hopper.TransferData;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.components.ItemFilterComponent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class LivingHopperFunction implements LivingItemFunction {

    public static final String ID = "living_hopper";
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int DEFAULT_COOLDOWN = 8;
    static final int DEFAULT_MAX_TRANSFER = 64;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.HOPPER) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= context.getSize()) continue;

            ItemStack stack = entry.stack();
            LivingHopperData data = LivingItemManager.getHopperData(stack);

            ContainerRedstoneData redstoneData = tick.getOrCreateRedstoneData(context);
            boolean hasRedstoneSignal = redstoneData.getSignal(slot) > 0;

            if (hasRedstoneSignal) {
                if (!data.disabled()) {
                    data = data.withDisabled(true);
                    LivingItemManager.setHopperData(stack, data);
                    context.syncSlotToClients(slot, stack);
                }
                continue;
            }

            if (data.disabled()) {
                data = data.withDisabled(false);
                LivingItemManager.setHopperData(stack, data);
                context.syncSlotToClients(slot, stack);
            }

            DirectionTransferData dir = data.direction();
            int containerSize = context.getSize();
            int containerWidth = context.getWidth();

            int sourceSlot = SlotResolver.resolve(slot, dir.sourceOffset(), containerSize, containerWidth);
            int targetSlot = SlotResolver.resolve(slot, dir.targetOffset(), containerSize, containerWidth);

            TransferData transfer = data.transfer();
            if (transfer.isOnCooldown()) {
                transfer = transfer.tick();
                LivingItemManager.setHopperData(stack, data.withTransfer(transfer));
                context.syncSlotToClients(slot, stack);
                continue;
            }

            FilterData filter = tick.getSnapshot().getFilterOf(slot);

            boolean transferred = executeTransfer(context, level, slot, sourceSlot, targetSlot,
                stack.getCount(), filter, dir, tick);

            PerfMetrics.recordTransfer(transferred);

            if (transferred) {
                int actualCooldown = Math.max(1, DEFAULT_COOLDOWN - stack.getCount() / 8);
                transfer = transfer.withCooldown(actualCooldown);
            }

            ResolvedSlotData slotInfo = new ResolvedSlotData(slot, sourceSlot, targetSlot, containerSize, containerWidth);
            data = data.withTransfer(transfer).withFilter(filter).withSlotInfo(slotInfo);
            LivingItemManager.setHopperData(stack, data);
            context.syncSlotToClients(slot, stack);
        }

        cleanupStaleRoutes(entries, context, tick);
    }

    private boolean executeTransfer(ContainerContext ctx, Level level, int hostSlot,
                                     int sourceSlot, int targetSlot,
                                     int stackSize, FilterData filter,
                                     DirectionTransferData dir,
                                     TickContext tick) {
        return TransferPipeline.execute(
            ctx, level, hostSlot, sourceSlot, targetSlot,
            stackSize, DEFAULT_MAX_TRANSFER, filter,
            ResolvedSlots.ofTransfer(sourceSlot, targetSlot, dir.sourceOffset(), dir.targetOffset()),
            dir, tick);
    }

    private void cleanupStaleRoutes(List<SlotEntry> entries, ContainerContext context, TickContext tick) {
        Set<Integer> activeSlots = new HashSet<>();
        for (SlotEntry entry : entries) {
            activeSlots.add(entry.slotIndex());
        }
        Set<Integer> activeEnderChestSlots = tick.getFunctionSlots(LivingEnderChestFunction.ID);
        // 末影箱槽位 → 当前频道键，使 validateRoutes 能识别堆叠数变化导致的模式切换
        Map<Integer, EnderChannelKey> targetKeysBySlot = EnderChannelKey.ofSlots(context, activeEnderChestSlots);
        EnderChannelRegistry.getInstance().validateRoutes(context, activeSlots, targetKeysBySlot);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingHopperData data = LivingItemManager.getHopperData(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.hopper.status"));

        if (data.disabled()) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.hopper.disabled")
                .withStyle(net.minecraft.ChatFormatting.RED));
        }

        DirectionTransferData dir = data.direction();
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.direction.transfer",
            dir.sourceOffset().getSymbol(),
            dir.targetOffset().getSymbol(),
            Component.literal(
                findMappingName(dir.sourceOffset(), dir.targetOffset()))
        ).withStyle(net.minecraft.ChatFormatting.GOLD));

        TransferData transfer = data.transfer();
        if (transfer.isOnCooldown()) {
            tooltipAdder.accept(Component.literal(
                "\u51b7\u5374\u4e2d: " + transfer.cooldown() + " ticks")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }

        FilterData filter = data.filter();
        if (filter != null && !filter.equals(FilterData.EMPTY)) {
            ItemFilterComponent.appendFilterTooltip(filter, tooltipAdder);
        }

        if (flag.isAdvanced()) {
            appendAdvancedTooltip(tooltipAdder, stack, data, dir, transfer);
        }
    }

    private void appendAdvancedTooltip(Consumer<Component> tooltipAdder,
                                        ItemStack stack,
                                        LivingHopperData data,
                                        DirectionTransferData dir,
                                        TransferData transfer) {
        int stackCount = stack.getCount();
        int actualCooldown = Math.max(1, DEFAULT_COOLDOWN - stackCount / 8);
        double rate = 20.0 / actualCooldown;
        int filterMode = ItemFilterComponent.normalizeMode(stackCount);
        ResolvedSlotData slotInfo = data.slotInfo();

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.hopper.advanced.title")
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC));

        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.hopper.advanced.stack",
            stackCount, actualCooldown, String.format("%.1f", rate))
            .withStyle(net.minecraft.ChatFormatting.GRAY));

        String modeStr = switch (filterMode) {
            case ItemFilterComponent.MODE_COMPONENT -> "NBT";
            case ItemFilterComponent.MODE_TAG -> "Tag";
            default -> "ID";
        };
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.hopper.advanced.filter_mode", modeStr)
            .withStyle(net.minecraft.ChatFormatting.GRAY));

        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.hopper.advanced.max_transfer", DEFAULT_MAX_TRANSFER)
            .withStyle(net.minecraft.ChatFormatting.GRAY));

        if (slotInfo.isValid()) {
            appendSlotInfo(tooltipAdder, slotInfo, dir);
        }

        if (transfer.isOnCooldown()) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.hopper.advanced.cooldown_detail",
                transfer.cooldown(), actualCooldown)
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        } else {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.hopper.advanced.ready")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        }

        boolean hasRules = data.filter() != null && !data.filter().equals(FilterData.EMPTY);
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.hopper.advanced.has_filter",
            hasRules ? Component.translatable("tooltip.livingitem.hopper.advanced.yes")
                .withStyle(net.minecraft.ChatFormatting.GREEN)
                : Component.translatable("tooltip.livingitem.hopper.advanced.no")
                .withStyle(net.minecraft.ChatFormatting.RED))
            .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    private void appendSlotInfo(Consumer<Component> tooltipAdder,
                                 ResolvedSlotData slotInfo,
                                 DirectionTransferData dir) {
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.hopper.advanced.container.title")
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));

        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.hopper.advanced.container.size",
            slotInfo.containerSize(), slotInfo.containerWidth(),
            slotInfo.containerSize() / slotInfo.containerWidth())
            .withStyle(net.minecraft.ChatFormatting.GRAY));

        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.hopper.advanced.container.host",
            slotInfo.hostSlot(),
            slotInfo.hostSlot() / slotInfo.containerWidth(),
            slotInfo.hostSlot() % slotInfo.containerWidth())
            .withStyle(net.minecraft.ChatFormatting.GRAY));

        String srcSymbol = dir.sourceOffset().getSymbol();
        String tgtSymbol = dir.targetOffset().getSymbol();

        if (slotInfo.sourceOutOfBounds()) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.hopper.advanced.slot.cross",
                srcSymbol, slotInfo.sourceSlot())
                .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        } else {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.hopper.advanced.slot.in_container",
                srcSymbol, slotInfo.sourceSlot(),
                slotInfo.sourceSlot() / slotInfo.containerWidth(),
                slotInfo.sourceSlot() % slotInfo.containerWidth())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        }

        if (slotInfo.targetOutOfBounds()) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.hopper.advanced.slot.cross",
                tgtSymbol, slotInfo.targetSlot())
                .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        } else {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.hopper.advanced.slot.in_container",
                tgtSymbol, slotInfo.targetSlot(),
                slotInfo.targetSlot() / slotInfo.containerWidth(),
                slotInfo.targetSlot() % slotInfo.containerWidth())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    private String findMappingName(Pos2D source, Pos2D target) {
        for (var preset : SlotMapping.PRESETS) {
            if (preset.sourceOffset().equals(source) && preset.targetOffset().equals(target)) {
                return preset.displayName();
            }
        }
        return source.getSymbol() + "-" + target.getSymbol();
    }

    public static boolean isLivingHopper(ItemStack stack) {
        return stack.is(Items.HOPPER) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean updateTransferMapping(ItemStack hopperStack, SlotMapping newMapping) {
        if (hopperStack == null || hopperStack.isEmpty() || newMapping == null) return false;
        LivingHopperData data = LivingItemManager.getHopperData(hopperStack);
        DirectionTransferData dir = data.direction()
            .withSource(newMapping.sourceOffset())
            .withTarget(newMapping.targetOffset());
        LivingItemManager.setHopperData(hopperStack, data.withDirection(dir));
        return true;
    }

    public static DirectionTransferData readDirectionData(ItemStack hopperStack) {
        return LivingItemManager.getHopperData(hopperStack).direction();
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}