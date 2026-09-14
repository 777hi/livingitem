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
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
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
            String containerKey = context.getContainerKey();

            // 从运行时缓存读取瞬态数据（不影响物品堆叠的 DataComponent）
            LivingItemRuntimeData cached = ContainerRuntimeCache.get(containerKey, slot);
            int cooldown = cached.isHopper() ? cached.hopper().cooldown() : 0;
            ResolvedSlotData slotInfo = cached.isHopper() && cached.hopper().slotInfo() != null
                ? cached.hopper().slotInfo() : ResolvedSlotData.EMPTY;
            data = data.withTransfer(new TransferData(cooldown)).withSlotInfo(slotInfo);

                boolean hasRedstoneSignal = tick.getSensor(context).maxSensedSignal(slot) > 0;

            if (hasRedstoneSignal) {
                if (!data.disabled()) {
                    data = data.withDisabled(true);
                    // 写入 DataComponent 时重置运行时字段，不影响物品堆叠
                    LivingItemManager.setHopperData(stack,
                        data.withTransfer(TransferData.DEFAULT).withSlotInfo(ResolvedSlotData.EMPTY));
                    context.syncSlotToClients(slot, stack);
                }
                continue;
            }

            if (data.disabled()) {
                data = data.withDisabled(false);
                LivingItemManager.setHopperData(stack,
                    data.withTransfer(TransferData.DEFAULT).withSlotInfo(ResolvedSlotData.EMPTY));
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
                // 只更新运行时缓存，不写入 DataComponent
                ContainerRuntimeCache.update(containerKey, slot,
                    LivingItemRuntimeData.forHopper(transfer.cooldown(), slotInfo));
                continue;
            }

            FilterData filter = tick.getSnapshot().getFilterOf(slot);

            // 过滤链回写：黑白名单是容器级派生数据（每 tick 由 HopperFilterBuilder 从
            // 容器布局+物品重建，不落盘），组件仅用于网络同步给客户端 tooltip
            // （living-hopper-tech.md §2.4.2）。规则变化时才写组件（稳态零写入）；
            // 漏斗搬去新容器后旧规则过期，下一 tick 用重建结果自愈。
            if (!filter.equals(LivingItemManager.getHopperFilter(stack))) {
                LivingItemManager.setHopperFilter(stack, filter);
                context.syncSlotToClients(slot, stack);
            }

            boolean transferred = executeTransfer(context, level, slot, sourceSlot, targetSlot,
                stack.getCount(), filter, dir, tick);

            PerfMetrics.recordTransfer(transferred);

            if (transferred) {
                int actualCooldown = Math.max(1, DEFAULT_COOLDOWN - stack.getCount() / 8);
                transfer = transfer.withCooldown(actualCooldown);
            }

            slotInfo = new ResolvedSlotData(slot, sourceSlot, targetSlot, containerSize, containerWidth);
            // 只更新运行时缓存（cooldown、slotInfo），不写入 DataComponent
            ContainerRuntimeCache.update(containerKey, slot,
                LivingItemRuntimeData.forHopper(transfer.cooldown(), slotInfo));
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

        // 从客户端缓存读取运行时数据（不影响物品堆叠）
        LivingItemRuntimeData runtimeData = LivingItemClientCache.getCurrentTooltipData();
        TransferData transfer;
        ResolvedSlotData slotInfo;
        if (runtimeData.isHopper()) {
            transfer = new TransferData(runtimeData.hopper().cooldown());
            slotInfo = runtimeData.hopper().slotInfo() != null
                ? runtimeData.hopper().slotInfo() : ResolvedSlotData.EMPTY;
        } else {
            transfer = data.transfer();
            slotInfo = data.slotInfo();
        }
        // 合并运行时数据到 DataComponent 数据用于 tooltip 显示
        data = data.withTransfer(transfer).withSlotInfo(slotInfo);

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

        if (transfer.isOnCooldown()) {
            tooltipAdder.accept(Component.literal(
                "\u51b7\u5374\u4e2d: " + transfer.cooldown() + " ticks")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }

        // 过滤链在独立组件 LIVING_HOPPER_FILTER 中（由 tick 从快照回写）
        FilterData filter = LivingItemManager.getHopperFilter(stack);
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

        boolean hasRules = LivingItemManager.getHopperFilter(stack) != null
            && !LivingItemManager.getHopperFilter(stack).equals(FilterData.EMPTY);
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
        // 过滤链是容器环境的派生数据（同一容器里两个漏斗的规则必然不同），
        // 不忽略会破坏漏斗堆叠；堆叠合并后下一 tick 由快照重建自愈
        return Set.of(LivingItemManager.LIVING_HOPPER_FILTER.value());
    }
}