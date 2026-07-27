/**
 * 跨容器传输工具类
 * 
 * 该类负责处理活漏斗在容器边界时与相邻容器的物品交互。
 * 当活漏斗的输入或输出槽位超出当前容器的范围时，会触发跨容器传输逻辑。
 * 
 * 核心功能：
 * 1. 从相邻容器拉取物品（pullFromNeighbor）
 * 2. 向相邻容器推送物品（pushToNeighbor）
 * 3. 在两个相邻容器之间直接传输（transferBetweenNeighbors）
 * 
 * 架构设计：
 * - 普通物品传输统一使用 SlotAccessor 架构（NeighborSlotAccessor + SlotAccessor.transfer）
 * - 活箱子/末影箱保留特殊逻辑（内部存储/路由注册/直连模式）
 * - 过滤由 FilteredSlotAccessor 自动处理，无需手动检查 filterState
 * 
 * 方向映射系统：
 * - 容器GUI的上下左右方向需要根据方块朝向转换为世界坐标方向
 * - 例如：当方块朝向北方时，GUI上方对应世界南方，GUI下方对应世界北方
 * 
 * 大箱子处理：
 * - 自动检测并合并大箱子的双容器实例
 * - 防止大箱子内部的无效传输（左侧箱子不会向右侧箱子传输）
 * - 根据传输方向选择正确的半箱作为基准位置：
 *   上方/左侧边界以LEFT半箱为基础，下方/右侧边界以RIGHT半箱为基础
 */
package com.qiqi.li.living.container;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.qiqi.li.living.function.LivingChestFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.accessor.EnderChannelEntry;
import com.qiqi.li.living.core.accessor.EnderChannelRegistry;
import com.qiqi.li.living.core.accessor.LivingEnderChestAccessor;
import com.qiqi.li.living.core.accessor.NeighborSlotAccessor;
import com.qiqi.li.living.core.accessor.SlotAccessor;
import com.qiqi.li.living.core.accessor.SlotAccessorFactory;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.ResolvedSlots;
import com.qiqi.li.living.data.FilterData;
import com.qiqi.li.living.core.components.ItemFilterComponent;
import com.qiqi.li.living.function.LivingChestFunction;
import com.qiqi.li.living.function.LivingEnderChestFunction;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

public final class CrossContainerTransfer {

    private CrossContainerTransfer() {}

    /**
     * 执行跨容器传输操作
     * 
     * 根据源槽位和目标槽位的越界情况，选择合适的传输模式：
     * - 源槽位越界：从相邻容器拉取物品到当前容器
     * - 目标槽位越界：从当前容器推送物品到相邻容器
     * - 都越界：在两个相邻容器之间直接传输
     * 
     * @param ctx 组件上下文，包含容器信息、槽位索引、偏移量等
     * @param stackSize 单次传输的最大物品数量
     * @param maxTransfer 本次操作允许的总传输量限制
     * @param hostSlot 活漏斗所在槽位
     * @return 是否成功执行了传输操作
     */
    public static boolean execute(ContainerContext containerCtx,
                                   ResolvedSlots resolvedSlots,
                                   Level level,
                                   FilterData filterData,
                                   int stackSize, int maxTransfer,
                                   int hostSlot,
                                   TickContext tick) {
        int containerSize = containerCtx.getSize();
        int sourceSlot = resolvedSlots.sourceSlot();
        int targetSlot = resolvedSlots.targetSlot();

        boolean sourceOutOfBounds = sourceSlot < 0 || sourceSlot >= containerSize;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (!sourceOutOfBounds && !targetOutOfBounds) return false;

        BlockPos containerPos = containerCtx.getBlockPos();
        if (level == null || containerPos == null) return false;

        BlockState blockState = level.getBlockState(containerPos);
        Direction blockFacing = getBlockFacing(blockState);
        if (blockFacing == null) return false;

        List<BlockPos> chestPositions = findDoubleChestPositions(level, containerPos);

        BlockPos sourceBasePos = getBasePosForDirection(containerPos, resolvedSlots.sourceOffset(), chestPositions);
        BlockPos targetBasePos = getBasePosForDirection(containerPos, resolvedSlots.targetOffset(), chestPositions);

        if (sourceOutOfBounds && !targetOutOfBounds) {
            return pullFromNeighbor(containerCtx, resolvedSlots, level, sourceBasePos,
                                     blockFacing, chestPositions, stackSize, maxTransfer, filterData, hostSlot, tick);
        }

        if (targetOutOfBounds && !sourceOutOfBounds) {
            return pushToNeighbor(containerCtx, resolvedSlots, level, targetBasePos,
                                   blockFacing, chestPositions, stackSize, maxTransfer, filterData, tick);
        }

        if (sourceOutOfBounds && targetOutOfBounds) {
            return transferBetweenNeighbors(containerCtx, resolvedSlots, level, sourceBasePos, targetBasePos,
                                             blockFacing, chestPositions, stackSize, maxTransfer, filterData, tick);
        }

        return false;
    }

    /**
     * 从相邻容器拉取物品到当前容器
     *
     * <p>当源槽位超出容器边界时调用此方法。</p>
     *
     * <p>对于普通物品，使用 SlotAccessor 架构统一传输（自动过滤）。
     * 对于活箱子/末影箱目标，保留特殊逻辑。</p>
     */
    private static boolean pullFromNeighbor(ContainerContext containerCtx,
                                             ResolvedSlots resolvedSlots, Level level,
                                             BlockPos basePos, Direction blockFacing,
                                             List<BlockPos> chestPositions,
                                             int stackSize, int maxTransfer,
                                             FilterData filterData,
                                             int hostSlot,
                                             TickContext tick) {
        Pos2D sourceOffset = resolvedSlots.sourceOffset();
        Direction sourceWorldDir = gridToWorld(sourceOffset, blockFacing);
        if (sourceWorldDir == null) return false;

        IItemHandler neighborHandler = getNeighborHandler(level, basePos, sourceWorldDir, chestPositions);
        if (neighborHandler == null) return false;

        int targetSlot = resolvedSlots.targetSlot();
        ItemStack targetStack = containerCtx.getItem(targetSlot);

        if (LivingChestFunction.isLivingChest(targetStack)) {
            return pullFromNeighborToLivingChest(containerCtx, level, neighborHandler,
                targetStack, targetSlot, stackSize, maxTransfer, filterData);
        }

        if (LivingEnderChestFunction.isLivingEnderChest(targetStack)) {
            return pullFromNeighborToLivingEnderChest(containerCtx, level, neighborHandler,
                basePos, sourceWorldDir, targetStack, targetSlot, stackSize, maxTransfer, filterData, hostSlot);
        }

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor target = SlotAccessorFactory.create(server, containerCtx, targetSlot, null,
            tick.transferredTargetSlots);
        if (target == null || target.isFull()) return false;

        int amount = Math.min(stackSize, maxTransfer);

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack sourceStack = neighborHandler.getStackInSlot(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            SlotAccessor source = SlotAccessorFactory.createForNeighbor(neighborHandler, i, filterData);
            if (SlotAccessor.transfer(source, target, amount)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 向相邻容器推送物品
     *
     * <p>当目标槽位超出容器边界时调用此方法。</p>
     *
     * <p>对于普通物品，使用 SlotAccessor 架构统一传输（自动过滤）。
     * 对于活箱子/末影箱源，保留特殊逻辑。</p>
     */
    private static boolean pushToNeighbor(ContainerContext containerCtx,
                                           ResolvedSlots resolvedSlots, Level level,
                                           BlockPos basePos, Direction blockFacing,
                                           List<BlockPos> chestPositions,
                                           int stackSize, int maxTransfer,
                                           FilterData filterData,
                                           TickContext tick) {
        int sourceSlot = resolvedSlots.sourceSlot();
        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        if (sourceStack.isEmpty()) return false;

        boolean sourceIsChest = LivingChestFunction.isLivingChest(sourceStack);
        boolean sourceIsEnderChest = LivingEnderChestFunction.isLivingEnderChest(sourceStack);
        if (!sourceIsChest && !sourceIsEnderChest && LivingItemManager.isLivingItem(sourceStack)) return false;

        Pos2D targetOffset = resolvedSlots.targetOffset();
        Direction targetWorldDir = gridToWorld(targetOffset, blockFacing);
        if (targetWorldDir == null) return false;

        IItemHandler neighborHandler = getNeighborHandler(level, basePos, targetWorldDir, chestPositions);
        if (neighborHandler == null) return false;

        if (sourceIsEnderChest) {
            return pushFromLivingEnderChestToNeighbor(containerCtx, level, neighborHandler,
                sourceStack, sourceSlot, stackSize, maxTransfer, filterData, tick);
        }

        if (sourceIsChest) {
            return pushFromLivingChestToNeighbor(containerCtx, level, neighborHandler,
                sourceStack, sourceSlot, stackSize, maxTransfer, filterData);
        }

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, containerCtx, sourceSlot,
            filterData, tick.transferredTargetSlots);
        if (source == null) return false;

        int amount = Math.min(stackSize, maxTransfer);

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack neighborStack = neighborHandler.getStackInSlot(i);
            if (!neighborStack.isEmpty() && !neighborStack.is(sourceStack.getItem())
                && neighborStack.getCount() >= neighborHandler.getSlotLimit(i)) continue;

            SlotAccessor target = SlotAccessorFactory.createForNeighbor(neighborHandler, i, null);
            if (SlotAccessor.transfer(source, target, amount)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 从活箱子提取物品并推送到相邻容器
     */
    private static boolean pushFromLivingChestToNeighbor(ContainerContext containerCtx,
                                                          Level level,
                                                          IItemHandler neighborHandler,
                                                          ItemStack chestStack,
                                                          int sourceSlot,
                                                          int stackSize,
                                                          int maxTransfer,
                                                          FilterData filterData) {
        if (level.isClientSide()) return false;

        var server = level.getServer();
        if (server == null) return false;

        int capacity = LivingChestFunction.getCapacity(containerCtx);
        int transferAmount = Math.min(stackSize, maxTransfer);

        if (LivingChestFunction.isStorageEmpty(chestStack)) {
            return false;
        }

        ItemStack matchingType = null;
        if (filterData != null && !filterData.equals(FilterData.EMPTY)) {
            List<ItemStack> merged = LivingChestFunction.getItems(chestStack);
            for (ItemStack item : merged) {
                if (!item.isEmpty() && ItemFilterComponent.allows(filterData, item)) {
                    matchingType = item;
                    break;
                }
            }
            if (matchingType == null) {
                return false;
            }
        }

        if (!hasAnySpace(neighborHandler)) return false;

        ItemStack extracted;
        if (matchingType != null) {
            extracted = LivingChestFunction.extractItem(chestStack, matchingType, transferAmount);
        } else {
            extracted = LivingChestFunction.extractItem(chestStack, transferAmount);
        }
        if (extracted.isEmpty()) return false;

        ItemStack remaining = tryInsert(neighborHandler, extracted);
        if (!remaining.isEmpty()) {
            LivingChestFunction.insertItem(chestStack, remaining);
        }
        return remaining.isEmpty();
    }

    /**
     * 从活末影箱提取物品并推送到相邻容器
     */
    private static boolean pushFromLivingEnderChestToNeighbor(ContainerContext containerCtx,
                                                               Level level,
                                                               IItemHandler neighborHandler,
                                                               ItemStack enderChestStack,
                                                               int sourceSlot,
                                                               int stackSize,
                                                               int maxTransfer,
                                                               FilterData filterData,
                                                               TickContext tick) {
        if (level.isClientSide()) return false;

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        int channel = enderChestStack.getCount();
        var transferredTargetSlots = tick.transferredTargetSlots;
        UUID boundUuid = LivingEnderChestFunction.getBoundPlayerUuid(enderChestStack);
        LivingEnderChestAccessor accessor;
        if (boundUuid != null) {
            accessor = new LivingEnderChestAccessor(server, channel, transferredTargetSlots, boundUuid);
        } else {
            accessor = new LivingEnderChestAccessor(server, channel, filterData, transferredTargetSlots);
        }

        if (!hasAnySpace(neighborHandler)) return false;

        int transferAmount = Math.min(stackSize, maxTransfer);
        ItemStack extracted = accessor.extract(transferAmount, ItemStack.EMPTY);
        if (extracted.isEmpty()) return false;

        ItemStack remaining = tryInsert(neighborHandler, extracted);
        if (!remaining.isEmpty()) {
            accessor.rollback(remaining);
        }
        return remaining.isEmpty();
    }

    /**
     * 从相邻容器拉取物品并插入活箱子
     */
    private static boolean pullFromNeighborToLivingChest(ContainerContext containerCtx,
                                                          Level level,
                                                          IItemHandler neighborHandler,
                                                          ItemStack chestStack,
                                                          int targetSlot,
                                                          int stackSize,
                                                          int maxTransfer,
                                                          FilterData filterData) {
        if (level.isClientSide()) return false;

        var server = level.getServer();
        if (server == null) return false;

        int capacity = LivingChestFunction.getCapacity(containerCtx);

        if (LivingChestFunction.isStorageFull(chestStack) || LivingChestFunction.isByteFull(chestStack, server.registryAccess())) {
            return false;
        }

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack sourceStack = neighborHandler.getStackInSlot(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            if (filterData != null && !ItemFilterComponent.allows(filterData, sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

            ItemStack toInsert = sourceStack.copy();
            toInsert.setCount(transferAmount);

            LivingChestFunction.insertItem(chestStack, toInsert, server.registryAccess());

            int inserted = transferAmount - toInsert.getCount();
            if (inserted > 0) {
                extractFromHandler(neighborHandler, i, inserted);
                return true;
            }
        }

        return false;
    }

    /**
     * 从相邻容器拉取物品并注册到活末影箱路由
     */
    private static boolean pullFromNeighborToLivingEnderChest(ContainerContext containerCtx,
                                                               Level level,
                                                               IItemHandler neighborHandler,
                                                               BlockPos basePos,
                                                               Direction sourceWorldDir,
                                                               ItemStack enderChestStack,
                                                               int targetSlot,
                                                               int stackSize,
                                                               int maxTransfer,
                                                               FilterData filterData,
                                                               int hostSlot) {
        if (level.isClientSide()) return false;

        var server = level.getServer();
        if (server == null) return false;

        UUID boundUuid = LivingEnderChestFunction.getBoundPlayerUuid(enderChestStack);
        if (boundUuid != null) {
            return pullFromNeighborToDirectEnderChest(server, neighborHandler,
                boundUuid, stackSize, maxTransfer, filterData);
        }

        BlockPos neighborPos = basePos.relative(sourceWorldDir);
        int channel = enderChestStack.getCount();
        var registry = EnderChannelRegistry.getInstance();

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack sourceStack = neighborHandler.getStackInSlot(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            if (filterData != null && !ItemFilterComponent.allows(filterData, sourceStack)) continue;

            String itemType = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
            var entry = new EnderChannelEntry(
                itemType, level.dimension(), neighborPos, i, hostSlot, null, targetSlot,
                containerCtx.getContainerKey());

            if (registry.contains(channel, entry)) {
                return true;
            }

            registry.removeByPositionAndSlotFromAllChannels(neighborPos, i);
            registry.insert(channel, entry);
            return true;
        }

        return false;
    }

    private static boolean pullFromNeighborToDirectEnderChest(MinecraftServer server,
                                                                IItemHandler neighborHandler,
                                                                UUID boundUuid,
                                                                int stackSize,
                                                                int maxTransfer,
                                                                FilterData filterData) {
        var player = server.getPlayerList().getPlayer(boundUuid);
        if (player == null) return false;

        var enderChest = player.getEnderChestInventory();

        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack sourceStack = neighborHandler.getStackInSlot(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            if (filterData != null && !ItemFilterComponent.allows(filterData, sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));
            ItemStack toInsert = sourceStack.copy();
            toInsert.setCount(transferAmount);

            ItemStack remaining = ItemHandlerHelper.insertItem(
                new net.neoforged.neoforge.items.wrapper.InvWrapper(enderChest), toInsert, false);

            int inserted = transferAmount - remaining.getCount();
            if (inserted > 0) {
                extractFromHandler(neighborHandler, i, inserted);
                return true;
            }
        }

        return false;
    }

    /**
     * 在两个相邻容器之间直接传输物品
     *
     * <p>使用 SlotAccessor 架构统一传输，自动处理过滤。</p>
     */
    private static boolean transferBetweenNeighbors(ContainerContext containerCtx,
                                                     ResolvedSlots resolvedSlots, Level level,
                                                     BlockPos sourceBasePos, BlockPos targetBasePos,
                                                     Direction blockFacing,
                                                     List<BlockPos> chestPositions,
                                                     int stackSize, int maxTransfer,
                                                     FilterData filterData,
                                                     TickContext tick) {
        Pos2D sourceOffset = resolvedSlots.sourceOffset();
        Direction sourceWorldDir = gridToWorld(sourceOffset, blockFacing);
        if (sourceWorldDir == null) return false;

        Pos2D targetOffset = resolvedSlots.targetOffset();
        Direction targetWorldDir = gridToWorld(targetOffset, blockFacing);
        if (targetWorldDir == null) return false;

        IItemHandler sourceHandler = getNeighborHandler(level, sourceBasePos, sourceWorldDir, chestPositions);
        if (sourceHandler == null) return false;

        IItemHandler targetHandler = getNeighborHandler(level, targetBasePos, targetWorldDir, chestPositions);
        if (targetHandler == null) return false;

        int amount = Math.min(stackSize, maxTransfer);

        for (int i = 0; i < sourceHandler.getSlots(); i++) {
            ItemStack sourceStack = sourceHandler.getStackInSlot(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            SlotAccessor source = SlotAccessorFactory.createForNeighbor(sourceHandler, i, filterData);

            for (int j = 0; j < targetHandler.getSlots(); j++) {
                SlotAccessor target = SlotAccessorFactory.createForNeighbor(targetHandler, j, null);
                if (SlotAccessor.transfer(source, target, amount)) {
                    return true;
                }
            }
        }

        return false;
    }

    // ========== 方向映射系统 ==========

    public static Direction gridToWorld(Pos2D gridDir, Direction blockFacing) {
        if (gridDir == null || gridDir.isNone()) return null;

        Direction normalized;
        if (gridDir.equals(Pos2D.UP))         normalized = Direction.SOUTH;
        else if (gridDir.equals(Pos2D.DOWN))  normalized = Direction.NORTH;
        else if (gridDir.equals(Pos2D.LEFT))  normalized = Direction.EAST;
        else if (gridDir.equals(Pos2D.RIGHT)) normalized = Direction.WEST;
        else return null;

        int rotations = getRotationCount(blockFacing);
        for (int i = 0; i < rotations; i++) {
            normalized = normalized.getClockWise(Direction.Axis.Y);
        }

        return normalized;
    }

    public static Pos2D worldToGrid(Direction worldDir, Direction blockFacing) {
        if (worldDir == null || worldDir.getAxis() == Direction.Axis.Y) return Pos2D.NONE;

        int rotations = getRotationCount(blockFacing);
        Direction normalized = worldDir;
        for (int i = 0; i < rotations; i++) {
            normalized = normalized.getCounterClockWise(Direction.Axis.Y);
        }

        return switch (normalized) {
            case NORTH -> Pos2D.DOWN;
            case SOUTH -> Pos2D.UP;
            case WEST  -> Pos2D.RIGHT;
            case EAST  -> Pos2D.LEFT;
            default    -> Pos2D.NONE;
        };
    }

    private static int getRotationCount(Direction blockFacing) {
        return switch (blockFacing) {
            case NORTH -> 0;
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    private static Direction getBlockFacing(BlockState state) {
        for (DirectionProperty prop : new DirectionProperty[]{
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING}) {
            if (state.hasProperty(prop)) {
                return state.getValue(prop);
            }
        }
        return null;
    }

    // ========== 大箱子处理 ==========

    private static BlockPos getBasePosForDirection(BlockPos defaultPos, Pos2D gridDir, List<BlockPos> chestPositions) {
        if (chestPositions.isEmpty() || gridDir == null || gridDir.isNone()) {
            return defaultPos;
        }
        boolean useLeft = gridDir.equals(Pos2D.DOWN) || gridDir.equals(Pos2D.RIGHT);
        if (!useLeft && !gridDir.equals(Pos2D.UP) && !gridDir.equals(Pos2D.LEFT)) {
            return defaultPos;
        }

        return useLeft ? chestPositions.get(0) : chestPositions.get(1);
    }

    private static List<BlockPos> findDoubleChestPositions(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof ChestBlock)) {
            return List.of();
        }

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) {
            return List.of();
        }

        Direction connectedDir = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connectedDir);

        if (chestType == ChestType.LEFT) {
            return List.of(pos, otherPos);
        } else {
            return List.of(otherPos, pos);
        }
    }

    private static IItemHandler getNeighborHandler(Level level, BlockPos basePos, Direction direction, List<BlockPos> chestPositions) {
        BlockPos neighborPos = basePos.relative(direction);

        if (!chestPositions.isEmpty() && chestPositions.contains(neighborPos)) {
            return null;
        }

        return level.getCapability(
            Capabilities.ItemHandler.BLOCK, neighborPos, direction.getOpposite());
    }

    // ========== 活箱子/末影箱专用工具方法 ==========

    private static boolean hasAnySpace(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) return true;
            if (stack.getCount() < handler.getSlotLimit(i)) return true;
        }
        return false;
    }

    private static ItemStack tryInsert(IItemHandler handler, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        return ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false);
    }

    private static ItemStack extractFromHandler(IItemHandler handler, int slot, int amount) {
        return handler.extractItem(slot, amount, false);
    }

    private static void markSlotTransferred(ContainerContext containerCtx, int slot, TickContext tick) {
        Set<Integer> transferredTargetSlots = tick.transferredTargetSlots;
        if (transferredTargetSlots != null) {
            transferredTargetSlots.add(slot);
        }
    }
}