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

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.SlotAccessor;
import com.qiqi.li.living.transfer.SlotAccessorFactory;
import com.qiqi.li.living.transfer.SlotInteractions;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.util.DoubleChestPositions;
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

        List<BlockPos> chestPositions = DoubleChestPositions.find(level, containerPos);

        // 大箱子（及任意多方块容器）：GUI 的一个方向可能对应**多个**世界外部面
        // （见 §6.4「GUI 4 方向 → 世界 6 面」），按 hostSlot 所在的那块定首选，其余作备选，
        // 逐个尝试 —— 首选失败（该面无容器 / 该面是内部贴合面）就退到下一个面。
        List<BlockPos> sourceBases = getBasePosCandidates(containerPos, hostSlot, chestPositions, containerSize);
        List<BlockPos> targetBases = getBasePosCandidates(containerPos, hostSlot, chestPositions, containerSize);

        if (sourceOutOfBounds && !targetOutOfBounds) {
            for (BlockPos base : sourceBases) {
                if (pullFromNeighbor(containerCtx, resolvedSlots, level, base,
                        blockFacing, chestPositions, stackSize, maxTransfer, filterData, hostSlot, tick)) {
                    return true;
                }
            }
            return false;
        }

        if (targetOutOfBounds && !sourceOutOfBounds) {
            for (BlockPos base : targetBases) {
                if (pushToNeighbor(containerCtx, resolvedSlots, level, base,
                        blockFacing, chestPositions, stackSize, maxTransfer, filterData, tick)) {
                    return true;
                }
            }
            return false;
        }

        if (sourceOutOfBounds && targetOutOfBounds) {
            // 源/目标各有多面：先试「同一块容器」的对齐组合，再试交叉组合
            int aligned = Math.min(sourceBases.size(), targetBases.size());
            for (int i = 0; i < aligned; i++) {
                if (transferBetweenNeighbors(containerCtx, resolvedSlots, level, sourceBases.get(i),
                        targetBases.get(i), blockFacing, chestPositions, stackSize, maxTransfer, filterData, tick)) {
                    return true;
                }
            }
            for (int i = 0; i < sourceBases.size(); i++) {
                for (int j = 0; j < targetBases.size(); j++) {
                    if (i == j) continue;
                    if (transferBetweenNeighbors(containerCtx, resolvedSlots, level, sourceBases.get(i),
                            targetBases.get(j), blockFacing, chestPositions, stackSize, maxTransfer, filterData, tick)) {
                        return true;
                    }
                }
            }
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
    // 包级可见：CrossContainerTransferFertilizeTest 直接驱动（推送方向的交互 + 隔离红线）
    static boolean tryPushToNeighbor(IItemHandler neighborHandler, BlockPos neighborPos,
                                      Level level, SlotAccessor source,
                                      int amount, ItemStack filterItem) {
        // 循环自守：非合法货物（活物品，非箱类）绝不进入通用插入/合并——活物品隔离的
        // 兜底防线，不依赖调用方是否已在入口拦过（调用点顺序变更也不破隔离，2026-09-15
        // 教训）。语义上活物品本来就不是合法货物（唯一定义点 SlotInteractions.isEligibleCargo），
        // 交互分发同样会拒绝它，这里只保证「不插入」这一步不被绕过。
        boolean interactionOnly = !SlotInteractions.isEligibleCargo(filterItem);

        Container container = ContainerContext.getContainer(level, neighborPos);
        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack neighborStack = neighborHandler.getStackInSlot(i);
            // 槽位交互分发（注册式，2026-09-15）：邻居槽被某条 SlotInteraction 接管
            // （骨粉 → 活耕地 = 施肥），否则照旧走通用插入。getStackInSlot 是 BE 容器
            // 实时引用，组件修改即刻生效；GUI 同步由耕地所在容器自身 tick 的
            // updatePlant 兜底。交互不生效 → 不扣货，继续下方通用插入。
            if (SlotInteractions.tryInteract(source, filterItem, neighborStack, level)) return true;
            if (interactionOnly) continue;   // 交互不成立即止：活物品不进通用插入（隔离红线）
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

        // 槽位交互分发（注册式，2026-09-15）：目标槽被某条 SlotInteraction 接管
        // （邻居骨粉 → 本容器活耕地 = 施肥），否则落回通用拉取。
        // 为什么必须前置：活耕地是活物品、非存储容器，SlotAccessorFactory.create
        // 对非箱类活物品直接返回 null（下方 target == null 即返回 false），
        // 通用拉取对它必然失败——交互就是它的「插入」语义。
        // 2026-09-15 实测 bug：这条分支原先缺失，表现为「跨容器骨粉 → 同容器活耕地」
        // 不施肥（反向推送正常）。
        if (SlotInteractions.tryInteractFromNeighbor(neighborHandler, neighborPos, targetStack, level, filterData)) {
            containerCtx.syncSlotToClients(targetSlot, targetStack);
            return true;   // 漏斗 tick 自然设冷却——一次交互 = 一次传输
        }

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
        // 活物品不作货物（隔离规则，唯一定义点 SlotInteractions.isEligibleCargo）：
        // 活骨粉是活物品 ⇒ 不是合法货物 ⇒ 漏斗不给它施肥（施肥属传输语义，见
        // SlotInteractions 的 isEligibleCargo javadoc）。活骨粉的手动用武之地在
        // GUI 右键（那里本就要求活化）。
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

        // 多方块容器下「不同基准块 + 不同方向」可能解析到同一个邻居方块 ——
        // 源=目标 就是自传，直接跳过（避免物品在自己容器里空转 / 记账错乱）。
        if (sourceNeighborPos.equals(targetNeighborPos)) return false;

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

    public static Direction getBlockFacing(BlockState state) {
        for (DirectionProperty prop : new DirectionProperty[]{
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING}) {
            if (state.hasProperty(prop)) {
                return state.getValue(prop);
            }
        }
        return null;
    }

    // ========== 大箱子处理 ==========

    /**
     * 跨容器传输的「基准块」候选列表（按优先级排序）。
     *
     * <p><b>为什么要多个候选</b>：多方块容器（大箱子等）在世界里占 N 个方块，
     * GUI 的一个方向可能对应<b>多个</b>外部世界面 —— 沿 facing 轴（GUI 上/下）
     * 每个块各有一个面；沿连接轴（GUI 左/右）只有一端是外部，另一端是内部贴合面
     * （由 {@link #getNeighborHandler} 挡掉）。所以不能像单方块容器那样只定一个基准块。</p>
     *
     * <p><b>排序规则</b>：发起传输的槽位属于哪一块，那块就是首选，其余按位置顺序作备选。
     * 调用方逐个尝试，某面无容器（或是内部面）就退到下一个面 —— 大箱子因此能同时
     * 覆盖「上下各两个面」，功能更丰富。</p>
     *
     * <p><b>附带收益</b>：本方法依赖「槽位段 ↔ 位置顺序」按 {@code containerSize / 块数}
     * 均分的假设，而该顺序由各模组的 IItemHandler 合并方式决定（未必等于
     * vanilla {@code ChestBlock.getContainer} 的顺序）。有了备选面兜底，
     * 假设反了最多是优先级不同，功能仍然成立 —— 无需再为顺序做内容探测。</p>
     */
    // 包级可见：CrossContainerTransferFaceSelectionTest 直接驱动（多方块容器的面候选排序），
    // 与 tryPushToNeighbor 同一惯例 —— 纯逻辑无需 mock Level 即可钉住。
    static List<BlockPos> getBasePosCandidates(BlockPos defaultPos, int hostSlot,
                                               List<BlockPos> chestPositions, int containerSize) {
        if (chestPositions.isEmpty()) {
            return List.of(defaultPos);
        }
        if (chestPositions.size() == 1) {
            return List.of(chestPositions.get(0));
        }

        int perBlock = containerSize / chestPositions.size();
        if (perBlock <= 0) {
            return List.of(chestPositions.get(0));
        }

        int primary = Math.min(Math.max(hostSlot / perBlock, 0), chestPositions.size() - 1);

        List<BlockPos> result = new ArrayList<>(chestPositions.size());
        result.add(chestPositions.get(primary));
        for (int i = 0; i < chestPositions.size(); i++) {
            if (i != primary) {
                result.add(chestPositions.get(i));
            }
        }
        return result;
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