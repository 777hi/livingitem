package com.qiqi.li.living.domain.hopper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.ender.EnderRouteManager;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.SlotAccessorFactory;
import com.qiqi.li.living.transfer.SlotInteractions;
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

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, ctx, sourceSlot, filter,
            transferredTargetSlots, tick.getSnapshot());

        // 槽位交互分发（注册式，2026-09-15）：货物 + 目标槽命中某条 SlotInteraction
        // 方程 → 由该交互接管本轮传输（骨粉 → 活耕地 = 施肥；今后新增交互只注册一条，
        // 本调用点不再改动，见 SlotInteractions）。
        // 位置在活物品隔离检查之前也无妨：交互层自己先过货物准入（isEligibleCargo，
        // 活物品不作货物）——施肥是传输语义，故活骨粉（活物品）不会被漏斗施肥；
        // 目标槽是活耕地时通用插入也必然失败（SlotAccessorFactory.create 对它返回 null）。
        // canInteract 廉价筛选在前：绝大多数组合不匹配 → 连 Accessor 都不额外分配。
        // 交互不生效（含 equals 零空转）→ 不扣货不设冷却，继续走下方通用路径。
        ItemStack targetStack = ctx.getItem(targetSlot);
        if (SlotInteractions.canInteract(sourceStack, targetStack)
            && SlotInteractions.tryInteract(source, sourceStack, targetStack, level)) {
            ctx.syncSlotToClients(targetSlot, targetStack);
            ctx.syncSlotToClients(sourceSlot, sourceStack);
            return true;   // 漏斗 tick 自然设冷却——一次交互 = 一次传输
        }

        if (!isTransferableSource(sourceStack)) return false;

        if (!isStorageContainer(sourceStack)
            && !ItemFilterComponent.allows(filter, sourceStack)) return false;

        Container hostContainer = ContainerContext.getContainer(level, ctx.getBlockPos());
        if (hostContainer != null && !hostContainer.canTakeItem(hostContainer, sourceSlot, sourceStack)) {
            return false;
        }

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

    /**
     * 活漏斗的货物准入：活物品不作货物，活箱子/活末影箱除外（它们是存储容器）。
     * 唯一定义点在 {@link SlotInteractions#isEligibleCargo}——传输层与交互层共用，
     * 避免「隔离规则」两处漂移（2026-09-15）。
     */
    private static boolean isTransferableSource(ItemStack stack) {
        return SlotInteractions.isEligibleCargo(stack);
    }

    private static boolean isStorageContainer(ItemStack stack) {
        return com.qiqi.li.living.domain.chest.LivingChestFunction.isLivingChest(stack)
            || LivingEnderChestFunction.isLivingEnderChest(stack);
    }
}