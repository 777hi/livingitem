package com.qiqi.li.living.domain.ender;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.UUID;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.transfer.SlotAccessor;

/**
 * 活末影箱路由管理器 —— 统一所有末影箱路由决策逻辑。
 *
 * <p>将散布在 {@code TransferPipeline} 和 {@code CrossContainerTransfer} 中的
 * 末影箱决策逻辑提炼为统一入口：</p>
 *
 * <ul>
 *   <li><b>同通道防护</b> —— 路由模式下同频道活末影箱之间禁止传输</li>
 *   <li><b>偏好类型设置</b> —— 输出槽已有物品时，设置源末影箱的偏好类型</li>
 *   <li><b>直连模式处理</b> —— 有绑定玩家时直接传输到玩家末影箱</li>
 *   <li><b>路由注册</b> —— 无绑定玩家时注册路由条目到全局路由表</li>
 * </ul>
 *
 * <h3>返回值语义</h3>
 * <ul>
 *   <li>{@link Decision#NOT_ENDER_CHEST} —— 目标不是活末影箱，调用方应走正常传输</li>
 *   <li>{@link Decision#REJECTED} —— 被防护拒绝（同通道），不传输</li>
 *   <li>{@link Decision#HANDLED} —— 已处理（路由注册或直连传输完成），调用方无需再操作</li>
 * </ul>
 */
public final class EnderRouteManager {

    private EnderRouteManager() {}

    public enum Decision {
        NOT_ENDER_CHEST,
        REJECTED,
        HANDLED
    }

    /**
     * 解析活末影箱目标决策（容器内传输场景）。
     *
     * <p>当传输目标为活末影箱时，决定如何处理：</p>
     * <ol>
     *   <li>同通道防护 → REJECTED</li>
     *   <li>偏好类型设置 → 设置源末影箱偏好</li>
     *   <li>直连模式 → 执行传输 → HANDLED</li>
     *   <li>路由模式 → 注册路由 → HANDLED</li>
     * </ol>
     *
     * @return 决策结果，NOT_ENDER_CHEST 表示目标不是活末影箱
     */
    public static Decision resolveTarget(SlotAccessor source, SlotAccessor target,
                                          ContainerContext ctx, Level level,
                                          int sourceSlot, int targetSlot, int hostSlot,
                                          int stackSize, int maxTransfer) {
        if (!(target.unwrap() instanceof LivingEnderChestAccessor enderChest)) {
            applySourceEnderChestPreference(source, ctx, targetSlot);
            return Decision.NOT_ENDER_CHEST;
        }

        if (isSameChannelGuard(source, enderChest)) {
            return Decision.REJECTED;
        }

        applySourceEnderChestPreference(source, ctx, targetSlot);

        if (enderChest.isDirectMode()) {
            SlotAccessor.transfer(source, target, Math.min(stackSize, maxTransfer));
            return Decision.HANDLED;
        }

        ItemStack srcStack = ctx.getItem(sourceSlot);
        if (!srcStack.isEmpty() && !com.qiqi.li.living.api.LivingItemManager.isLivingItem(srcStack)) {
            enderChest.registerRoute(srcStack, ctx, sourceSlot, hostSlot, targetSlot);
        }
        return Decision.HANDLED;
    }

    /**
     * 解析活末影箱目标决策（跨容器传输场景）。
     *
     * <p>源在邻居容器中，目标为容器内的活末影箱。
     * 路由模式下直接注册路由条目到全局路由表。</p>
     *
     * @return true 表示已处理（路由注册或直连传输），false 表示无可用源
     */
    public static boolean resolveCrossContainerTarget(
            ContainerContext containerCtx, Level level,
            net.neoforged.neoforge.items.IItemHandler neighborHandler,
            BlockPos neighborPos,
            ItemStack enderChestStack, int targetSlot,
            int stackSize, int maxTransfer,
            com.qiqi.li.living.transfer.FilterData filterData,
            int hostSlot) {

        if (level.isClientSide()) return false;
        MinecraftServer server = level.getServer();
        if (server == null) return false;

        UUID boundUuid = LivingEnderChestFunction.getBoundPlayerUuid(enderChestStack);

        if (boundUuid != null) {
            return directInsertFromNeighbor(server, neighborHandler, boundUuid,
                stackSize, maxTransfer, filterData, level, neighborPos);
        }

        int channel = enderChestStack.getCount();
        return registerRouteFromNeighbor(channel, neighborHandler, neighborPos,
            level, containerCtx, targetSlot, filterData, hostSlot);
    }

    /**
     * 同通道防护：路由模式下，同频道的两个活末影箱之间禁止传输。
     */
    private static boolean isSameChannelGuard(SlotAccessor source, LivingEnderChestAccessor targetEnder) {
        if (targetEnder.isDirectMode()) return false;
        if (!(source.unwrap() instanceof LivingEnderChestAccessor sourceEnder)) return false;
        if (sourceEnder.isDirectMode()) return false;
        return sourceEnder.getChannel() == targetEnder.getChannel();
    }

    /**
     * 偏好类型设置：当源是路由模式末影箱且目标槽有物品时，
     * 设置源的偏好类型以实现贪心提取策略。
     */
    private static void applySourceEnderChestPreference(SlotAccessor source,
                                                         ContainerContext ctx,
                                                         int targetSlot) {
        if (!(source.unwrap() instanceof LivingEnderChestAccessor sourceEnder)) return;
        if (sourceEnder.isDirectMode()) return;

        ItemStack targetStack = ctx.getItem(targetSlot);
        if (!targetStack.isEmpty()) {
            sourceEnder.setPreferredItemType(
                BuiltInRegistries.ITEM.getKey(targetStack.getItem()).toString());
        }
    }

    /**
     * 从邻居容器注册路由到全局路由表（路由模式）。
     */
    private static boolean registerRouteFromNeighbor(int channel,
                                                      net.neoforged.neoforge.items.IItemHandler neighborHandler,
                                                      BlockPos neighborPos,
                                                      Level level,
                                                      ContainerContext containerCtx,
                                                      int targetSlot,
                                                      com.qiqi.li.living.transfer.FilterData filterData,
                                                      int hostSlot) {
        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack sourceStack = neighborHandler.getStackInSlot(i);
            if (sourceStack.isEmpty()) continue;
            if (com.qiqi.li.living.api.LivingItemManager.isLivingItem(sourceStack)) continue;
            if (filterData != null && !com.qiqi.li.living.components.ItemFilterComponent.allows(filterData, sourceStack)) continue;

            String itemType = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
            EnderChannelEntry entry = new EnderChannelEntry(
                itemType, level.dimension(), neighborPos, i, hostSlot, null, targetSlot,
                containerCtx.getContainerKey());

            if (registry.contains(channel, entry)) {
                return true;
            }

            registry.removeByPositionAndSlotFromAllChannels(neighborPos, i);
            registry.offer(channel, entry);
            return true;
        }

        return false;
    }

    /**
     * 直连模式：从邻居容器提取物品并插入到绑定玩家的末影箱。
     */
    private static boolean directInsertFromNeighbor(MinecraftServer server,
                                                     net.neoforged.neoforge.items.IItemHandler neighborHandler,
                                                     UUID boundUuid,
                                                     int stackSize, int maxTransfer,
                                                     com.qiqi.li.living.transfer.FilterData filterData,
                                                     Level level, BlockPos neighborPos) {
        var player = server.getPlayerList().getPlayer(boundUuid);
        if (player == null) return false;

        var enderChest = player.getEnderChestInventory();
        var enderHandler = new net.neoforged.neoforge.items.wrapper.InvWrapper(enderChest);

        int amount = Math.min(stackSize, maxTransfer);

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack sourceStack = neighborHandler.getStackInSlot(i);
            if (sourceStack.isEmpty()) continue;
            if (com.qiqi.li.living.api.LivingItemManager.isLivingItem(sourceStack)) continue;

            var source = com.qiqi.li.living.transfer.SlotAccessorFactory.createForNeighbor(
                neighborHandler, i, filterData, level, neighborPos);

            for (int j = 0; j < enderHandler.getSlots(); j++) {
                var target = com.qiqi.li.living.transfer.SlotAccessorFactory.createForNeighbor(
                    enderHandler, j, null, null, null);
                if (SlotAccessor.transfer(source, target, amount)) {
                    return true;
                }
            }
        }

        return false;
    }
}