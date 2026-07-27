package com.qiqi.li.living.function;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.LivingItemFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.CrossContainerTransfer;
import com.qiqi.li.living.core.SlotResolver;
import com.qiqi.li.living.core.accessor.SlotAccessor;
import com.qiqi.li.living.core.accessor.SlotAccessorFactory;
import com.qiqi.li.living.core.accessor.LivingEnderChestAccessor;
import com.qiqi.li.living.data.*;
import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.ResolvedSlots;
import com.qiqi.li.living.core.model.SlotMapping;
import com.qiqi.li.living.core.accessor.EnderChannelRegistry;
import com.qiqi.li.living.data.DirectionTransferData;
import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.data.LivingHopperData;
import com.qiqi.li.living.data.TransferData;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class LivingHopperFunction implements LivingItemFunction {

    public static final String ID = "living_hopper";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_COOLDOWN = 8;
    private static final int DEFAULT_MAX_TRANSFER = 64;

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

            FilterData filter = data.filter();
            boolean transferred = executeTransfer(context, level, slot, sourceSlot, targetSlot,
                stack.getCount(), filter, dir, tick);

            // 记录传输结果
            PerfMetrics.recordTransfer(transferred);

            if (transferred) {
                int actualCooldown = Math.max(1, DEFAULT_COOLDOWN - stack.getCount() / 8);
                transfer = transfer.withCooldown(actualCooldown);
            }

            LivingItemManager.setHopperData(stack, data.withTransfer(transfer));
            context.syncSlotToClients(slot, stack);
        }

        cleanupStaleRoutes(entries, context, level);
    }

    private boolean executeTransfer(ContainerContext ctx, Level level, int hostSlot,
                                     int sourceSlot, int targetSlot,
                                     int stackSize, FilterData filter,
                                     DirectionTransferData dir,
                                     TickContext tick) {
        int containerSize = ctx.getSize();

        boolean sourceOutOfBounds = sourceSlot < 0 || sourceSlot >= containerSize;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (sourceOutOfBounds || targetOutOfBounds) {
            return CrossContainerTransfer.execute(
                ctx,
                ResolvedSlots.ofTransfer(
                    sourceSlot, targetSlot, dir.sourceOffset(), dir.targetOffset()),
                level, filter,
                stackSize, DEFAULT_MAX_TRANSFER, hostSlot, tick);
        }

        if (sourceSlot == targetSlot) return false;

        Set<Integer> transferredTargetSlots = tick.transferredTargetSlots;
        if (transferredTargetSlots != null && transferredTargetSlots.contains(sourceSlot)) {
            return false;
        }

        ItemStack sourceStack = ctx.getItem(sourceSlot);
        if (sourceStack.isEmpty()) return false;

        boolean isStorageContainer = LivingChestFunction.isLivingChest(sourceStack)
            || LivingEnderChestFunction.isLivingEnderChest(sourceStack);

        if (LivingItemManager.isLivingItem(sourceStack) && !isStorageContainer) return false;

        if (!isStorageContainer && !ItemFilterComponent.allows(filter, sourceStack)) return false;

        var server = level.getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, ctx, sourceSlot, filter, transferredTargetSlots);
        SlotAccessor target = SlotAccessorFactory.create(server, ctx, targetSlot, null, transferredTargetSlots);

        if (source == null || target == null) return false;

        if (source.unwrap() instanceof LivingEnderChestAccessor sourceEnder
            && target.unwrap() instanceof LivingEnderChestAccessor targetEnder) {
            if (!sourceEnder.isDirectMode() && !targetEnder.isDirectMode()
                && sourceEnder.getChannel() == targetEnder.getChannel()) {
                return false;
            }
        }

        if (target.unwrap() instanceof LivingEnderChestAccessor enderChest) {
            if (enderChest.isDirectMode()) {
                return SlotAccessor.transfer(source, target, Math.min(stackSize, DEFAULT_MAX_TRANSFER));
            }
            ItemStack srcStack = ctx.getItem(sourceSlot);
            if (!srcStack.isEmpty() && !LivingItemManager.isLivingItem(srcStack)) {
                enderChest.registerRoute(srcStack, ctx, sourceSlot, hostSlot);
            }
            return true;
        }

        return SlotAccessor.transfer(source, target, Math.min(stackSize, DEFAULT_MAX_TRANSFER));
    }

    private void cleanupStaleRoutes(List<SlotEntry> entries, ContainerContext context, Level level) {
        Set<Integer> activeSlots = new HashSet<>();
        Set<Integer> activeEnderChestSlots = new HashSet<>();
        for (SlotEntry entry : entries) {
            activeSlots.add(entry.slotIndex());
        }

        int containerSize = context.getSize();
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = context.getItem(i);
            if (LivingEnderChestFunction.isLivingEnderChest(stack)) {
                activeEnderChestSlots.add(i);
            }
        }

        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        BlockPos pos = context.getBlockPos();
        if (pos != null) {
            registry.removeStaleRoutes(pos, activeSlots);
        } else {
            String containerKey = context.getContainerKey();
            if (containerKey != null) {
                registry.removeStaleRoutes(containerKey, activeSlots);
            }
        }
        registry.removeStaleEnderChestRoutes(activeEnderChestSlots);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingHopperData data = LivingItemManager.getHopperData(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.hopper.status"));

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
                "冷却中: " + transfer.cooldown() + " ticks")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }

        FilterData filter = data.filter();
        if (!filter.equals(FilterData.EMPTY)) {
            ItemFilterComponent.appendFilterTooltip(filter, tooltipAdder);
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