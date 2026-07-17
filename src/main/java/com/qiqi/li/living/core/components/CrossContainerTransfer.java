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
package com.qiqi.li.living.core.components;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.LivingChestFunction;
import com.qiqi.li.living.LivingHopperFunction;

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
     * 大箱子基准位置选择：
     * 对于大箱子，不同方向的跨容器传输需要基于不同半箱的位置：
     * - 上方/左侧方向：以LEFT半箱为基准（LEFT半箱对应GUI上半部分，槽位0-26）
     * - 下方/右侧方向：以RIGHT半箱为基准（RIGHT半箱对应GUI下半部分，槽位27-53）
     * 
     * @param ctx 组件上下文，包含容器信息、槽位索引、偏移量等
     * @param stackSize 单次传输的最大物品数量
     * @param maxTransfer 本次操作允许的总传输量限制
     * @return 是否成功执行了传输操作
     */
    public static boolean execute(ComponentContext ctx,
                                   int stackSize, int maxTransfer) {
        ContainerContext containerCtx = ctx.containerCtx();
        int containerSize = containerCtx.getSize();
        int sourceSlot = ctx.sourceSlot();
        int targetSlot = ctx.targetSlot();

        boolean sourceOutOfBounds = sourceSlot < 0 || sourceSlot >= containerSize;
        boolean targetOutOfBounds = targetSlot < 0 || targetSlot >= containerSize;

        if (!sourceOutOfBounds && !targetOutOfBounds) return false;

        Level level = containerCtx.getLevel();
        BlockPos containerPos = containerCtx.getBlockPos();
        if (level == null || containerPos == null) return false;

        BlockState blockState = level.getBlockState(containerPos);
        Direction blockFacing = getBlockFacing(blockState);
        if (blockFacing == null) return false;

        List<BlockPos> chestPositions = findDoubleChestPositions(level, containerPos);

        BlockPos sourceBasePos = getBasePosForDirection(containerPos, ctx.sourceOffset(), chestPositions);
        BlockPos targetBasePos = getBasePosForDirection(containerPos, ctx.targetOffset(), chestPositions);

        if (sourceOutOfBounds && !targetOutOfBounds) {
            return pullFromNeighbor(ctx, containerCtx, level, sourceBasePos,
                                     blockFacing, chestPositions, stackSize, maxTransfer);
        }

        if (targetOutOfBounds && !sourceOutOfBounds) {
            return pushToNeighbor(ctx, containerCtx, level, targetBasePos,
                                   blockFacing, chestPositions, stackSize, maxTransfer);
        }

        if (sourceOutOfBounds && targetOutOfBounds) {
            return transferBetweenNeighbors(ctx, containerCtx, level, sourceBasePos, targetBasePos,
                                             blockFacing, chestPositions, stackSize, maxTransfer);
        }

        return false;
    }
    /**
     * 从相邻容器拉取物品到当前容器
     * 
     * 当源槽位超出容器边界时调用此方法。
     * 根据源槽位的偏移量确定相邻容器的位置，
     * 然后从该容器中寻找合适的物品并传输到目标槽位。
     * 
     * 传输规则：
     * - 跳过空槽位和活物品
     * - 优先传输到空的目标槽位
     * - 如果目标槽位有相同物品且未满栈，则堆叠
     * - 单次只传输一个槽位的物品
     * 
     * @param ctx 组件上下文
     * @param containerCtx 当前容器上下文
     * @param level 世界对象
     * @param basePos 基准方块位置（根据方向选择的大箱子半箱位置）
     * @param blockFacing 方块朝向
     * @param chestPositions 大箱子的两个半箱位置（空列表表示非大箱子）
     * @param stackSize 单次传输最大数量
     * @param maxTransfer 总传输量限制
     * @return 是否成功拉取物品
     */
    private static boolean pullFromNeighbor(ComponentContext ctx,
                                             ContainerContext containerCtx, Level level,
                                             BlockPos basePos, Direction blockFacing,
                                             List<BlockPos> chestPositions,
                                             int stackSize, int maxTransfer) {
        Pos2D sourceOffset = ctx.sourceOffset();
        Direction sourceWorldDir = gridToWorld(sourceOffset, blockFacing);
        if (sourceWorldDir == null) return false;

        Container neighborContainer = getNeighborContainer(level, basePos, sourceWorldDir, chestPositions);
        if (neighborContainer == null) return false;

        int targetSlot = ctx.targetSlot();
        ItemStack targetStack = containerCtx.getItem(targetSlot);

        boolean targetIsChest = LivingChestFunction.isLivingChest(targetStack);

        if (targetIsChest) {
            return pullFromNeighborToLivingChest(ctx, containerCtx, neighborContainer,
                targetStack, targetSlot, stackSize, maxTransfer);
        }

        for (int i = 0; i < neighborContainer.getContainerSize(); i++) {
            ItemStack sourceStack = neighborContainer.getItem(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

            if (targetStack.isEmpty()) {
                ItemStack toTransfer = sourceStack.copy();
                toTransfer.setCount(transferAmount);
                containerCtx.setItem(targetSlot, toTransfer);

                sourceStack.shrink(transferAmount);
                neighborContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            } else if (targetStack.is(sourceStack.getItem()) &&
                       targetStack.getCount() < targetStack.getMaxStackSize()) {
                int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                int actualTransfer = Math.min(transferAmount, spaceAvailable);

                targetStack.grow(actualTransfer);
                containerCtx.setItem(targetSlot, targetStack);

                sourceStack.shrink(actualTransfer);
                neighborContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            }
        }

        return false;
    }

    /**
     * 向相邻容器推送物品
     * 
     * 当目标槽位超出容器边界时调用此方法。
     * 从当前容器的源槽位获取物品，根据目标槽位的偏移量确定相邻容器位置，
     * 然后将物品传输到该容器中。
     * 
     * 传输规则：
     * - 检查源槽位是否有可传输的物品（非空且非活物品）
     * - 优先传输到目标容器的空槽位
     * - 如果有相同物品且未满栈，则堆叠
     * - 单次只传输一个槽位的物品
     * 
     * @param ctx 组件上下文
     * @param containerCtx 当前容器上下文
     * @param level 世界对象
     * @param basePos 基准方块位置（根据方向选择的大箱子半箱位置）
     * @param blockFacing 方块朝向
     * @param chestPositions 大箱子的两个半箱位置（空列表表示非大箱子）
     * @param stackSize 单次传输最大数量
     * @param maxTransfer 总传输量限制
     * @return 是否成功推送物品
     */
    private static boolean pushToNeighbor(ComponentContext ctx,
                                           ContainerContext containerCtx, Level level,
                                           BlockPos basePos, Direction blockFacing,
                                           List<BlockPos> chestPositions,
                                           int stackSize, int maxTransfer) {
        int sourceSlot = ctx.sourceSlot();
        ItemStack sourceStack = containerCtx.getItem(sourceSlot);
        if (sourceStack.isEmpty()) return false;

        boolean sourceIsChest = LivingChestFunction.isLivingChest(sourceStack);
        if (!sourceIsChest && LivingItemManager.isLivingItem(sourceStack)) return false;

        Pos2D targetOffset = ctx.targetOffset();
        Direction targetWorldDir = gridToWorld(targetOffset, blockFacing);
        if (targetWorldDir == null) return false;

        Container neighborContainer = getNeighborContainer(level, basePos, targetWorldDir, chestPositions);
        if (neighborContainer == null) return false;

        if (sourceIsChest) {
            return pushFromLivingChestToNeighbor(ctx, containerCtx, neighborContainer,
                sourceStack, sourceSlot, stackSize, maxTransfer);
        }

        int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

        for (int j = 0; j < neighborContainer.getContainerSize(); j++) {
            ItemStack targetStack = neighborContainer.getItem(j);

            if (targetStack.isEmpty()) {
                ItemStack toTransfer = sourceStack.copy();
                toTransfer.setCount(transferAmount);
                neighborContainer.setItem(j, toTransfer);

                sourceStack.shrink(transferAmount);
                containerCtx.setItem(sourceSlot, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            } else if (targetStack.is(sourceStack.getItem()) &&
                       targetStack.getCount() < targetStack.getMaxStackSize()) {
                int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                int actualTransfer = Math.min(transferAmount, spaceAvailable);

                targetStack.grow(actualTransfer);
                neighborContainer.setItem(j, targetStack);

                sourceStack.shrink(actualTransfer);
                containerCtx.setItem(sourceSlot, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                return true;
            }
        }

        return false;
    }

    /**
     * 从活箱子提取物品并推送到相邻容器
     * 
     * 当活漏斗的输出槽位超出容器边界，且输入槽位是活箱子时调用。
     * 从活箱子中提取物品，然后推送到相邻容器的合适槽位中。
     * 
     * 传输规则：
     * - 从活箱子中提取 stackSize 数量的物品
     * - 在相邻容器中寻找空槽位或可合并的槽位
     * - 优先推送到空槽位，其次堆叠到已有同类物品的槽位
     * - 如果相邻容器无法接收，剩余物品退回活箱子
     * 
     * @param ctx 组件上下文
     * @param containerCtx 当前容器上下文
     * @param neighborContainer 相邻容器
     * @param chestStack 活箱子物品栈
     * @param sourceSlot 活箱子所在的槽位索引
     * @param stackSize 单次传输最大数量
     * @param maxTransfer 总传输量限制
     * @return 是否成功推送物品
     */
    private static boolean pushFromLivingChestToNeighbor(ComponentContext ctx,
                                                          ContainerContext containerCtx,
                                                          Container neighborContainer,
                                                          ItemStack chestStack,
                                                          int sourceSlot,
                                                          int stackSize,
                                                          int maxTransfer) {
        if (ctx.level().isClientSide()) return false;

        var server = ctx.level().getServer();
        if (server == null) return false;

        int capacity = LivingChestFunction.getCapacity(containerCtx);
        int transferAmount = Math.min(stackSize, maxTransfer);

        // 前置判断：活箱子为空则跳过
        ComponentState chestState = LivingChestFunction.getStorageState(chestStack);
        if (InternalStorageComponent.isStorageEmpty(chestState)) {
            return false;
        }

        ItemStack extracted = LivingChestFunction.extractItem(server, chestStack, transferAmount, capacity);
        if (extracted.isEmpty()) return false;

        for (int j = 0; j < neighborContainer.getContainerSize(); j++) {
            ItemStack targetStack = neighborContainer.getItem(j);

            if (targetStack.isEmpty()) {
                neighborContainer.setItem(j, extracted.copy());
                return true;
            } else if (targetStack.is(extracted.getItem()) &&
                       targetStack.getCount() < targetStack.getMaxStackSize()) {
                int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                int actualTransfer = Math.min(extracted.getCount(), spaceAvailable);

                targetStack.grow(actualTransfer);
                neighborContainer.setItem(j, targetStack);
                extracted.shrink(actualTransfer);

                if (!extracted.isEmpty()) {
                    LivingChestFunction.insertItem(server, chestStack, extracted, capacity);
                }
                return true;
            }
        }

        LivingChestFunction.insertItem(server, chestStack, extracted, capacity);
        return false;
    }

    /**
     * 从相邻容器拉取物品并插入活箱子
     * 
     * 当活漏斗的输入槽位超出容器边界，且输出槽位是活箱子时调用。
     * 从相邻容器中寻找可传输的物品，然后插入到活箱子中。
     * 
     * 传输规则：
     * - 遍历相邻容器的所有槽位，跳过空槽位和活物品
     * - 找到物品后，插入到活箱子中
     * - 如果插入成功，从相邻容器中消耗对应数量的物品
     * - 如果插入失败（活箱子满），尝试下一个槽位
     * 
     * @param ctx 组件上下文
     * @param containerCtx 当前容器上下文
     * @param neighborContainer 相邻容器
     * @param chestStack 活箱子物品栈
     * @param targetSlot 活箱子所在的槽位索引
     * @param stackSize 单次传输最大数量
     * @param maxTransfer 总传输量限制
     * @return 是否成功拉取物品
     */
    private static boolean pullFromNeighborToLivingChest(ComponentContext ctx,
                                                          ContainerContext containerCtx,
                                                          Container neighborContainer,
                                                          ItemStack chestStack,
                                                          int targetSlot,
                                                          int stackSize,
                                                          int maxTransfer) {
        if (ctx.level().isClientSide()) return false;

        var server = ctx.level().getServer();
        if (server == null) return false;

        int capacity = LivingChestFunction.getCapacity(containerCtx);

        // 前置判断：活箱子已满则跳过
        ComponentState chestState = LivingChestFunction.getStorageState(chestStack);
        if (InternalStorageComponent.isStorageFull(chestState, capacity)) {
            return false;
        }

        for (int i = 0; i < neighborContainer.getContainerSize(); i++) {
            ItemStack sourceStack = neighborContainer.getItem(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

            ItemStack toInsert = sourceStack.copy();
            toInsert.setCount(transferAmount);

            LivingChestFunction.insertItem(server, chestStack, toInsert, capacity);

            int inserted = transferAmount - toInsert.getCount();
            if (inserted > 0) {
                sourceStack.shrink(inserted);
                neighborContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);
                return true;
            }
        }

        return false;
    }

    /**
     * 在两个相邻容器之间直接传输物品
     * 
     * 当源槽位和目标槽位都超出容器边界时调用此方法。
     * 这种情况通常发生在活漏斗位于两个容器交界处时，
     * 需要从一个相邻容器拉取物品并推送到另一个相邻容器。
     * 
     * 传输规则：
     * - 分别确定源方向和目标方向的相邻容器
     * - 从源容器寻找可传输的物品
     * - 传输到目标容器的合适槽位
     * - 跳过空槽位、活物品以及已满栈的情况
     * 
     * @param ctx 组件上下文
     * @param containerCtx 当前容器上下文
     * @param level 世界对象
     * @param sourceBasePos 源方向基准位置
     * @param targetBasePos 目标方向基准位置
     * @param blockFacing 方块朝向
     * @param chestPositions 大箱子的两个半箱位置（空列表表示非大箱子）
     * @param stackSize 单次传输最大数量
     * @param maxTransfer 总传输量限制
     * @return 是否成功在相邻容器间传输物品
     */
    private static boolean transferBetweenNeighbors(ComponentContext ctx,
                                                     ContainerContext containerCtx, Level level,
                                                     BlockPos sourceBasePos, BlockPos targetBasePos,
                                                     Direction blockFacing,
                                                     List<BlockPos> chestPositions,
                                                     int stackSize, int maxTransfer) {
        Pos2D sourceOffset = ctx.sourceOffset();
        Direction sourceWorldDir = gridToWorld(sourceOffset, blockFacing);
        if (sourceWorldDir == null) return false;

        Pos2D targetOffset = ctx.targetOffset();
        Direction targetWorldDir = gridToWorld(targetOffset, blockFacing);
        if (targetWorldDir == null) return false;

        Container sourceContainer = getNeighborContainer(level, sourceBasePos, sourceWorldDir, chestPositions);
        if (sourceContainer == null) return false;

        Container targetContainer = getNeighborContainer(level, targetBasePos, targetWorldDir, chestPositions);
        if (targetContainer == null) return false;

        for (int i = 0; i < sourceContainer.getContainerSize(); i++) {
            ItemStack sourceStack = sourceContainer.getItem(i);
            if (sourceStack.isEmpty() || LivingItemManager.isLivingItem(sourceStack)) continue;

            int transferAmount = Math.min(sourceStack.getCount(), Math.min(stackSize, maxTransfer));

            for (int j = 0; j < targetContainer.getContainerSize(); j++) {
                ItemStack targetStack = targetContainer.getItem(j);

                if (targetStack.isEmpty()) {
                    ItemStack toTransfer = sourceStack.copy();
                    toTransfer.setCount(transferAmount);
                    targetContainer.setItem(j, toTransfer);

                    sourceStack.shrink(transferAmount);
                    sourceContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                    return true;
                } else if (targetStack.is(sourceStack.getItem()) &&
                           targetStack.getCount() < targetStack.getMaxStackSize()) {
                    int spaceAvailable = targetStack.getMaxStackSize() - targetStack.getCount();
                    int actualTransfer = Math.min(transferAmount, spaceAvailable);

                    targetStack.grow(actualTransfer);
                    targetContainer.setItem(j, targetStack);

                    sourceStack.shrink(actualTransfer);
                    sourceContainer.setItem(i, sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack);

                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 将容器GUI网格方向转换为世界坐标方向
     * 
     * 容器内的GUI使用二维坐标系（上下左右），需要根据方块的朝向
     * 转换为三维世界的实际方向（东南西北）。
     * 
     * 基准映射（当方块朝向北方时）：
     * - GUI上方（UP） -> 世界南方（SOUTH）- 箱子后方
     * - GUI下方（DOWN）-> 世界北方（NORTH）- 箱子前方  
     * - GUI左方（LEFT）-> 世界东方（EAST）- 箱子右方
     * - GUI右方（RIGHT）-> 世界西方（WEST）- 箱子左方
     * 
     * 然后根据方块的实际朝向进行旋转：
     * - 朝北：0次旋转
     * - 朝东：顺时针旋转90度
     * - 朝南：旋转180度
     * - 朝西：逆时针旋转90度（或270度）
     * 
     * @param gridDir 容器内GUI的相对方向（UP/DOWN/LEFT/RIGHT）
     * @param blockFacing 方块的实际朝向
     * @return 对应的世界坐标方向，如果无法转换则返回null
     */
    public static Direction gridToWorld(Pos2D gridDir, Direction blockFacing) {
        if (gridDir == null || gridDir.isNone()) return null;

        Direction normalized;
        if (gridDir.equals(Pos2D.UP)) {
            normalized = Direction.SOUTH;
        } else if (gridDir.equals(Pos2D.DOWN)) {
            normalized = Direction.NORTH;
        } else if (gridDir.equals(Pos2D.LEFT)) {
            normalized = Direction.EAST;
        } else if (gridDir.equals(Pos2D.RIGHT)) {
            normalized = Direction.WEST;
        } else {
            return null;
        }

        int rotations = getRotationCount(blockFacing);
        for (int i = 0; i < rotations; i++) {
            normalized = normalized.getClockWise(Direction.Axis.Y);
        }

        return normalized;
    }

    /**
     * 将世界坐标方向转换为容器GUI网格方向
     * 
     * 这是gridToWorld的逆操作，将世界的实际方向转换回容器GUI的相对方向。
     * 主要用于确定相邻容器的位置对应到容器内的哪个方向。
     * 
     * 转换步骤：
     * 1. 根据方块朝向进行反向旋转，将世界方向归一化到基准方向
     * 2. 将归一化后的方向映射到GUI方向
     * 
     * 注意：只处理水平方向（东南西北），垂直方向（上下）返回NONE
     * 
     * @param worldDir 世界坐标方向
     * @param blockFacing 方块的实际朝向
     * @return 对应的GUI网格方向，如果无法转换或为垂直方向则返回Pos2D.NONE
     */
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

    /**
     * 根据方块朝向计算旋转次数
     * 
     * 用于方向转换时的旋转计算。以朝北为基准（0次旋转），
     * 其他方向按顺时针计算需要的旋转次数。
     * 
     * @param blockFacing 方块的朝向
     * @return 需要的顺时针旋转次数（0-3），非水平方向返回0
     */
    private static int getRotationCount(Direction blockFacing) {
        return switch (blockFacing) {
            case NORTH -> 0;
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    /**
     * 获取方块的朝向属性
     * 
     * 从方块状态中提取朝向信息。支持两种常见的朝向属性：
     * 1. FACING：完整的六面朝向（上下南北东西），用于漏斗、观察者等
     * 2. HORIZONTAL_FACING：仅水平四面朝向（南北东西），用于箱子、床等
     * 
     * @param state 方块的状态对象
     * @return 方块的朝向方向，如果没有朝向属性则返回null
     */
    private static Direction getBlockFacing(BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    /**
     * 根据GUI网格方向确定跨容器传输的基准方块位置。
     * 
     * 对于大箱子（双箱合并），不同边界的跨容器传输需要基于不同半箱的位置：
     * - 上方和左侧边界：以LEFT半箱为基础
     * - 下方和右侧边界：以RIGHT半箱为基础
     * 
     * 原因：
     * - LEFT半箱对应GUI上半部分（槽位0-26），所以上边界的相邻容器在LEFT半箱的上方
     * - RIGHT半箱对应GUI下半部分（槽位27-53），所以下边界的相邻容器在RIGHT半箱的下方
     * - LEFT半箱在物理空间的左侧，所以左边界的相邻容器在LEFT半箱的左方
     * - RIGHT半箱在物理空间的右侧，所以右边界的相邻容器在RIGHT半箱的右方
     * 
     * 对于普通容器或单箱子，直接返回默认位置。
     * 
     * @param defaultPos 默认方块位置（containerCtx.getBlockPos()）
     * @param gridDir GUI网格方向偏移
     * @param chestPositions 大箱子半箱位置列表，[0]=LEFT位置, [1]=RIGHT位置；空列表表示非大箱子
     * @return 对应方向的基准方块位置
     */
    private static BlockPos getBasePosForDirection(BlockPos defaultPos, Pos2D gridDir, List<BlockPos> chestPositions) {
        if (chestPositions.isEmpty() || gridDir == null || gridDir.isNone()) {
            return defaultPos;
        }

        boolean useLeft = gridDir.equals(Pos2D.DOWN) || gridDir.equals(Pos2D.RIGHT);
        boolean useRight = gridDir.equals(Pos2D.UP) || gridDir.equals(Pos2D.LEFT);

        if (!useLeft && !useRight) {
            return defaultPos;
        }

        return useLeft ? chestPositions.get(0) : chestPositions.get(1);
    }

    /**
     * 查找大箱子的两个半箱位置。
     * 
     * 如果给定位置是双箱合并的大箱子，返回两个半箱的位置，
     * 按顺序排列：[LEFT半箱位置, RIGHT半箱位置]。
     * 如果不是大箱子，返回空列表。
     * 
     * LEFT/RIGHT的判定基于方块的ChestType属性：
     * - ChestType.LEFT：当前方块是左半箱
     * - ChestType.RIGHT：当前方块是右半箱
     * 
     * @param level 世界对象
     * @param pos 方块位置
     * @return 半箱位置列表，[0]=LEFT位置, [1]=RIGHT位置；空列表表示非大箱子
     */
    private static List<BlockPos> findDoubleChestPositions(Level level, BlockPos pos) {
        List<BlockPos> positions = new ArrayList<>();
        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType != ChestType.SINGLE) {
                Direction connectedDir = ChestBlock.getConnectedDirection(state);
                BlockPos otherPos = pos.relative(connectedDir);

                if (chestType == ChestType.LEFT) {
                    positions.add(pos);
                    positions.add(otherPos);
                } else {
                    positions.add(otherPos);
                    positions.add(pos);
                }
            }
        }

        return positions;
    }

    /**
     * 获取指定方向的相邻容器
     * 
     * 根据基准位置和指定方向，查找并返回相邻位置的容器。
     * 该方法处理了多种容器类型的特殊情况：
     * 
     * 1. 箱子（ChestBlockEntity）：
     *    - 检测是否为大箱子（双箱合并）
     *    - 如果是大箱子，获取合并后的完整容器实例
     *    - 如果是单箱，直接使用箱子本身
     * 
     * 2. 其他容器（Container接口）：
     *    - 直接使用实现了Container接口的方块实体
     * 
     * 3. 大箱子内部传输防护：
     *    - 通过位置比较，防止在大箱子左右部分之间传输
     *    - 如果相邻位置是大箱子的另一个半箱，返回null
     * 
     * @param level 世界对象
     * @param basePos 基准方块位置（根据方向选择的大箱子半箱位置）
     * @param direction 要查找的方向
     * @param chestPositions 当前大箱子的半箱位置列表（空列表表示非大箱子）
     * @return 相邻容器对象，如果不存在或为同一大箱子则返回null
     */
    private static Container getNeighborContainer(Level level, BlockPos basePos, Direction direction, List<BlockPos> chestPositions) {
        BlockPos neighborPos = basePos.relative(direction);

        if (!chestPositions.isEmpty() && chestPositions.contains(neighborPos)) {
            return null;
        }

        BlockEntity neighborBe = level.getBlockEntity(neighborPos);
        if (neighborBe == null) return null;

        Container neighborContainer = null;

        if (neighborBe instanceof ChestBlockEntity chest) {
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ChestBlock chestBlock) {
                ChestType chestType = neighborState.getValue(ChestBlock.TYPE);
                if (chestType != ChestType.SINGLE) {
                    neighborContainer = ChestBlock.getContainer(chestBlock, neighborState, level, neighborPos, false);
                } else {
                    neighborContainer = chest;
                }
            } else {
                neighborContainer = chest;
            }
        } else if (neighborBe instanceof Container container) {
            neighborContainer = container;
        }

        return neighborContainer;
    }
}