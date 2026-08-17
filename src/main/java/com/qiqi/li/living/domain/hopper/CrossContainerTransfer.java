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
 * - Container 接口过滤（canTakeItem/canPlaceItem）模拟玩家操作，兼容各类容器
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
package com.qiqi.li.living.domain.hopper;

import java.util.List;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.SlotAccessorFactory;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.ResolvedSlots;
import com.qiqi.li.living.transfer.FilterData;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

public final class CrossContainerTransfer {

    private CrossContainerTransfer() {}

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

    // ========== 邻居槽位遍历核心方法 ==========

    /**
     * 遍历邻居容器的所有槽位，尝试拉取物品到目标 Accessor。
     * 通过 Container.canTakeItem 过滤不可交互槽位，模拟玩家操作。
     */
    private static boolean tryPullFromNeighbor(IItemHandler neighborHandler, BlockPos neighborPos,
                                                Level level, FilterData filter,
                                                SlotAccessor target, int amount) {
        Container container = ContainerContext.getContainer(level, neighborPos);
        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack stack = neighborHandler.getStackInSlot(i);
            if (stack.isEmpty() || LivingItemManager.isLivingItem(stack)) continue;
            if (container != null && !container.canTakeItem(container, i, stack)) continue;
            SlotAccessor source = SlotAccessorFactory.createForNeighbor(neighborHandler, i, filter, level, neighborPos);
            if (SlotAccessor.transfer(source, target, amount)) return true;
        }
        return false;
    }

    /**
     * 遍历邻居容器的所有槽位，尝试从源 Accessor 推送物品。
     * 通过 Container.canPlaceItem 过滤不可交互槽位，模拟玩家操作。
     */
    private static boolean tryPushToNeighbor(IItemHandler neighborHandler, BlockPos neighborPos,
                                              Level level, SlotAccessor source,
                                              int amount, ItemStack filterItem) {
        Container container = ContainerContext.getContainer(level, neighborPos);
        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack neighborStack = neighborHandler.getStackInSlot(i);
            if (!neighborStack.isEmpty() && !neighborStack.is(filterItem.getItem())
                && neighborStack.getCount() >= neighborHandler.getSlotLimit(i)) continue;
            if (container != null && !container.canPlaceItem(i, filterItem)) continue;
            SlotAccessor target = SlotAccessorFactory.createForNeighbor(neighborHandler, i, null, level, neighborPos);
            if (SlotAccessor.transfer(source, target, amount)) return true;
        }
        return false;
    }

    // ========== 拉取：从相邻容器到当前容器 ==========

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
        BlockPos neighborPos = basePos.relative(sourceWorldDir);

        if (LivingChestFunction.isLivingChest(targetStack)) {
            return pullFromNeighborToLivingChest(containerCtx, level, neighborHandler,
                targetStack, targetSlot, stackSize, maxTransfer, filterData, tick, neighborPos);
        }

        if (LivingEnderChestFunction.isLivingEnderChest(targetStack)) {
            return com.qiqi.li.living.domain.ender.EnderRouteManager.resolveCrossContainerTarget(
                containerCtx, level, neighborHandler, neighborPos,
                targetStack, targetSlot, stackSize, maxTransfer, filterData, hostSlot);
        }

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor target = SlotAccessorFactory.create(server, containerCtx, targetSlot, null,
            tick.transferredTargetSlots, tick.getSnapshot());
        if (target == null || target.isFull()) return false;

        return tryPullFromNeighbor(neighborHandler, neighborPos, level, filterData,
            target, Math.min(stackSize, maxTransfer));
    }

    private static boolean pullFromNeighborToLivingChest(ContainerContext containerCtx,
                                                          Level level,
                                                          IItemHandler neighborHandler,
                                                          ItemStack chestStack,
                                                          int targetSlot,
                                                          int stackSize,
                                                          int maxTransfer,
                                                          FilterData filterData,
                                                          TickContext tick,
                                                          BlockPos neighborPos) {
        if (level.isClientSide()) return false;

        var server = level.getServer();
        if (server == null) return false;

        SlotAccessor target = SlotAccessorFactory.create(server, containerCtx, targetSlot,
            null, tick.transferredTargetSlots, tick.getSnapshot());
        if (target == null || target.isFull()) return false;

        return tryPullFromNeighbor(neighborHandler, neighborPos, level, filterData,
            target, Math.min(stackSize, maxTransfer));
    }

    // ========== 推送：从当前容器到相邻容器 ==========

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

        BlockPos neighborPos = basePos.relative(targetWorldDir);

        MinecraftServer server = level.getServer();
        if (server == null) return false;

        SlotAccessor source = SlotAccessorFactory.create(server, containerCtx, sourceSlot,
            filterData, tick.transferredTargetSlots, tick.getSnapshot());
        if (source == null || source.isEmpty()) return false;

        int amount = Math.min(stackSize, maxTransfer);
        ItemStack simulated = source.simulateExtract(amount);
        if (simulated.isEmpty()) return false;

        return tryPushToNeighbor(neighborHandler, neighborPos, level, source, amount, simulated);
    }

    // ========== 邻居间直接传输 ==========

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
        BlockPos sourceNeighborPos = sourceBasePos.relative(sourceWorldDir);
        BlockPos targetNeighborPos = targetBasePos.relative(targetWorldDir);

        Container sourceContainer = ContainerContext.getContainer(level, sourceNeighborPos);

        for (int i = 0; i < sourceHandler.getSlots(); i++) {
            ItemStack sourceStack = sourceHandler.getStackInSlot(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;
            if (sourceContainer != null && !sourceContainer.canTakeItem(sourceContainer, i, sourceStack)) continue;

            SlotAccessor source = SlotAccessorFactory.createForNeighbor(sourceHandler, i, filterData, level, sourceNeighborPos);
            if (tryPushToNeighbor(targetHandler, targetNeighborPos, level, source, amount, sourceStack)) return true;
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
}