package com.qiqi.li.living.domain.hopper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.ender.LivingEnderChestAccessor;
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
 *   <li><b>解析</b> —— 确定源/目标 SlotAccessor（容器内 or 邻居容器）</li>
 *   <li><b>前置检查</b> —— 空栈、活物品、过滤、级联防护</li>
 *   <li><b>末影箱决策</b> —— 同通道防护、路由注册、直连模式</li>
 *   <li><b>执行</b> —— {@link SlotAccessor#transfer}</li>
 * </ol>
 *
 * <p>跨容器场景（源/目标越界）委托给 {@link CrossContainerTransfer}，
 * 后者内部也使用 SlotAccessor 架构，保证 {@code setChanged()} 一致调用。</p>
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

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, ctx, sourceSlot, filter,
            transferredTargetSlots, tick.getSnapshot());
        SlotAccessor target = SlotAccessorFactory.create(server, ctx, targetSlot, null,
            transferredTargetSlots, tick.getSnapshot());

        if (source == null || target == null) return false;

        EnderChestDecision decision = resolveEnderChestDecision(source, target, ctx, level,
            sourceSlot, targetSlot, hostSlot, stackSize, maxTransfer);
        if (decision != null) return decision.result;

        return SlotAccessor.transfer(source, target, Math.min(stackSize, maxTransfer));
    }

    private static boolean isTransferableSource(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return true;
        return isStorageContainer(stack);
    }

    private static boolean isStorageContainer(ItemStack stack) {
        return com.qiqi.li.living.domain.chest.LivingChestFunction.isLivingChest(stack)
            || LivingEnderChestFunction.isLivingEnderChest(stack);
    }

    private static EnderChestDecision resolveEnderChestDecision(
            SlotAccessor source, SlotAccessor target,
            ContainerContext ctx, Level level,
            int sourceSlot, int targetSlot, int hostSlot,
            int stackSize, int maxTransfer) {

        if (source.unwrap() instanceof LivingEnderChestAccessor sourceEnder
            && target.unwrap() instanceof LivingEnderChestAccessor targetEnder) {
            if (!sourceEnder.isDirectMode() && !targetEnder.isDirectMode()
                && sourceEnder.getChannel() == targetEnder.getChannel()) {
                return EnderChestDecision.NO_TRANSFER;
            }
        }

        if (source.unwrap() instanceof LivingEnderChestAccessor sourceEnder
            && !sourceEnder.isDirectMode()) {
            ItemStack targetStack = ctx.getItem(targetSlot);
            if (!targetStack.isEmpty()) {
                sourceEnder.setPreferredItemType(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(targetStack.getItem()).toString());
            }
        }

        if (target.unwrap() instanceof LivingEnderChestAccessor enderChest) {
            if (enderChest.isDirectMode()) {
                boolean ok = SlotAccessor.transfer(source, target, Math.min(stackSize, maxTransfer));
                return new EnderChestDecision(ok);
            }
            ItemStack srcStack = ctx.getItem(sourceSlot);
            if (!srcStack.isEmpty() && !LivingItemManager.isLivingItem(srcStack)) {
                enderChest.registerRoute(srcStack, ctx, sourceSlot, hostSlot, targetSlot);
            }
            return EnderChestDecision.SUCCESS;
        }

        return null;
    }

    private static final class EnderChestDecision {
        static final EnderChestDecision NO_TRANSFER = new EnderChestDecision(false);
        static final EnderChestDecision SUCCESS = new EnderChestDecision(true);
        final boolean result;

        EnderChestDecision(boolean result) {
            this.result = result;
        }
    }
}