package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.TransferTargetPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 活末影箱的传输目标策略 —— 把 {@link EnderRouteManager} 的三态决策接入
 * {@code TransferTargetPolicies} 分发（薄适配，决策逻辑全部留在路由管理器）。
 */
final class EnderTransferPolicy implements TransferTargetPolicy {

    @Override
    public boolean matches(ItemStack stack) {
        return LivingEnderChestFunction.isLivingEnderChest(stack);
    }

    @Override
    public Outcome interceptInContainer(SlotAccessor source, SlotAccessor target,
                                        ContainerContext ctx, Level level,
                                        int sourceSlot, int targetSlot, int hostSlot,
                                        int stackSize, int maxTransfer) {
        return toOutcome(EnderRouteManager.resolveTarget(source, target, ctx, level,
            sourceSlot, targetSlot, hostSlot, stackSize, maxTransfer));
    }

    @Override
    public void onProbingTarget(SlotAccessor source, ContainerContext ctx,
                                int targetSlot, ItemStack targetStack) {
        // 目标无策略接管时同样设置偏好（对应旧 resolveTarget 的非末影目标路径）
        EnderRouteManager.applySourceEnderChestPreference(source, ctx, targetSlot);
    }

    @Override
    public boolean interceptCrossContainerPull(ContainerContext containerCtx, Level level,
                                               IItemHandler neighborHandler, BlockPos neighborPos,
                                               ItemStack targetStack, int targetSlot,
                                               int stackSize, int maxTransfer,
                                               FilterData filterData, int hostSlot) {
        return EnderRouteManager.resolveCrossContainerTarget(containerCtx, level,
            neighborHandler, neighborPos, targetStack, targetSlot,
            stackSize, maxTransfer, filterData, hostSlot);
    }

    private static Outcome toOutcome(EnderRouteManager.Decision decision) {
        return switch (decision) {
            case NOT_ENDER_CHEST -> Outcome.CONTINUE;
            case REJECTED -> Outcome.REJECT;
            case HANDLED -> Outcome.HANDLED;
        };
    }
}
