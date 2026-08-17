package com.qiqi.li.living.domain.hopper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.ender.EnderRouteManager;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.SlotAccessorFactory;
import com.qiqi.li.living.components.ItemFilterComponent;
import com.qiqi.li.living.model.ResolvedSlots;

/**
 * 传输管道 —— 统一活漏斗的物品传输流程。
 *
 * <p>将 {@code LivingHopperFunction.executeTransfer} 和
 * {@code CrossContainerTransfer.execute} 的公共逻辑提炼为管道阶段：</p>
 *
 * <ol>
 *   <li><b>路由分发</b> —— 容器内 vs 跨容器</li>
 *   <li><b>前置检查</b> —— 空栈、活物品、过滤、级联防护</li>
 *   <li><b>末影箱决策</b> —— 委托给 {@link EnderRouteManager}</li>
 *   <li><b>执行</b> —— {@link SlotAccessor#transfer}</li>
 * </ol>
 */
public final class TransferPipeline {

    private TransferPipeline() {}

    public static boolean execute(ContainerContext ctx, Level level,
                                   int hostSlot, int sourceSlot, int targetSlot,
                                   int stackSize, int maxTransfer,
                                   FilterData filter,
                                   ResolvedSlots resolvedSlots,
                                   DirectionTransferData dir,
                                   TickContext tick) {
        int containerSize = ctx.getSize();

        boolean sourceOutOfBounds = sourceSlot < 0 || sourceSlot >= containerSize;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (sourceOutOfBounds || targetOutOfBounds) {
            return CrossContainerTransfer.execute(
                ctx, resolvedSlots, level, filter,
                stackSize, maxTransfer, hostSlot, tick);
        }

        return executeInContainer(ctx, level, hostSlot, sourceSlot, targetSlot,
            stackSize, maxTransfer, filter, tick);
    }

    private static boolean executeInContainer(ContainerContext ctx, Level level,
                                               int hostSlot, int sourceSlot, int targetSlot,
                                               int stackSize, int maxTransfer,
                                               FilterData filter,
                                               TickContext tick) {
        if (sourceSlot == targetSlot) return false;

        var transferredTargetSlots = tick.transferredTargetSlots;
        if (transferredTargetSlots != null && transferredTargetSlots.contains(sourceSlot)) {
            return false;
        }

        ItemStack sourceStack = ctx.getItem(sourceSlot);
        if (sourceStack.isEmpty()) return false;

        if (!isTransferableSource(sourceStack)) return false;

        if (!isStorageContainer(sourceStack)
            && !ItemFilterComponent.allows(filter, sourceStack)) return false;

        Container hostContainer = ContainerContext.getContainer(level, ctx.getBlockPos());
        if (hostContainer != null && !hostContainer.canTakeItem(hostContainer, sourceSlot, sourceStack)) {
            return false;
        }

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, ctx, sourceSlot, filter,
            transferredTargetSlots, tick.getSnapshot());
        SlotAccessor target = SlotAccessorFactory.create(server, ctx, targetSlot, null,
            transferredTargetSlots, tick.getSnapshot());

        if (source == null || target == null) return false;

        int transferAmount = Math.min(stackSize, maxTransfer);

        if (hostContainer != null) {
            ItemStack simulated = source.simulateExtract(transferAmount);
            if (!simulated.isEmpty() && !hostContainer.canPlaceItem(targetSlot, simulated)) {
                return false;
            }
        }

        EnderRouteManager.Decision decision = EnderRouteManager.resolveTarget(
            source, target, ctx, level, sourceSlot, targetSlot, hostSlot, stackSize, maxTransfer);

        return switch (decision) {
            case NOT_ENDER_CHEST -> SlotAccessor.transfer(source, target, transferAmount);
            case REJECTED -> false;
            case HANDLED -> true;
        };
    }

    private static boolean isTransferableSource(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return true;
        return isStorageContainer(stack);
    }

    private static boolean isStorageContainer(ItemStack stack) {
        return com.qiqi.li.living.domain.chest.LivingChestFunction.isLivingChest(stack)
            || LivingEnderChestFunction.isLivingEnderChest(stack);
    }
}