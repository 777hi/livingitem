package com.qiqi.li.living;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 活物品容器处理器 —— 负责遍历容器中的物品并执行活物品 tick。
 *
 * 职责：
 * 1. 将不同类型的 Container 统一转换为 {@link ContainerContext}
 * 2. 遍历容器中所有物品，对活物品执行已注册功能的 tick 逻辑
 * 3. 处理大箱子的去重（避免左右两半被分别处理导致速度翻倍）
 *
 * 容器类型与 Context 构建策略：
 * - 玩家背包（Inventory）：直接包装为 SimpleContainerContext
 * - 单箱子（ChestBlockEntity）：直接包装，associatedBlockEntities 包含自身
 * - 大箱子（两个 ChestBlockEntity）：
 *     dataContainer = ChestBlock.getContainer() 返回的 CompoundContainer（统一索引 0-53）
 *     associatedBlockEntities = 两个 ChestBlockEntity（用于匹配玩家菜单中的 slot）
 *
 * 为什么大箱子需要同时保存 CompoundContainer 和 ChestBlockEntity：
 *   CompoundContainer 提供统一的 getItem/setItem 接口（索引 0-53），
 *   但玩家菜单中的 slot.container 指向的是 ChestBlockEntity 本身。
 *   同步时需要通过 ItemStack 引用匹配来找到正确的菜单槽位。
 */
public class ContainerLivingItemHandler {
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 处理容器中的所有活物品。
     * 用于玩家背包等简单容器场景。
     *
     * @param container 容器（如玩家背包）
     * @param level 世界
     */
    public static void processContainer(Container container, Level level) {
        if (level.isClientSide) return;
        ContainerContext context = buildContext(container, level);
        processContext(context, level);
    }

    /**
     * 根据容器类型构建对应的 ContainerContext。
     *
     * @param container 容器
     * @param level 世界
     * @return 包装后的容器上下文
     */
    public static ContainerContext buildContext(Container container, Level level) {
        if (container instanceof ChestBlockEntity chest) {
            return buildChestContext(chest, level);
        }
        return new SimpleContainerContext(container, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * 为 ChestBlockEntity 构建容器上下文。
     *
     * 核心策略：
     * - dataContainer（组合容器）用于数据读写（getItem/setItem）
     *   因为它提供了大箱子场景下的统一索引（单箱 0-26，大箱 0-53）
     * - associatedBlockEntities（关联的 BlockEntity）用于同步时匹配玩家菜单
     *   因为玩家 ChestMenu 中的 slot.container 指向 ChestBlockEntity 本身
     * - associatedBlockPositions 用于生成稳定的容器标识 key
     *
     * @param chest 箱子方块实体
     * @param level 世界
     * @return 构建好的容器上下文
     */
    private static ContainerContext buildChestContext(ChestBlockEntity chest, Level level) {
        BlockPos pos = chest.getBlockPos();
        BlockState state = level.getBlockState(pos);

        List<BlockEntity> associatedBlockEntities = new ArrayList<>();
        List<BlockPos> associatedBlockPositions = new ArrayList<>();

        associatedBlockEntities.add(chest);
        associatedBlockPositions.add(pos);

        if (state.getBlock() instanceof ChestBlock chestBlock) {
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType != ChestType.SINGLE) {
                Direction direction = ChestBlock.getConnectedDirection(state);
                BlockPos otherPos = pos.relative(direction);
                BlockEntity otherBe = level.getBlockEntity(otherPos);
                if (otherBe instanceof ChestBlockEntity otherChest) {
                    associatedBlockEntities.add(otherChest);
                    associatedBlockPositions.add(otherPos);
                }
            }

            Container container = ChestBlock.getContainer(chestBlock, state, level, pos, false);
            if (container != null) {
                return new SimpleContainerContext(container, associatedBlockPositions, associatedBlockEntities);
            }
        }

        return new SimpleContainerContext(chest, associatedBlockPositions, associatedBlockEntities);
    }

    /**
     * 遍历容器中所有物品，按功能分组后统一调用 tick。
     *
     * 两阶段设计：
     * 1. 扫描阶段：遍历容器中所有物品，将活物品按功能类型分组收集
     * 2. 执行阶段：对每种功能只调用一次 tick，传入该容器中所有拥有此功能的活物品列表
     *
     * 为什么按功能分组调用而非逐个调用：
     * 如果逐个调用 tick，每个活物品独立推进自己的状态，
     * 导致总速度随活物品数量线性增长（N 个活熔炉 = N 倍速度）。
     * 按功能分组后，由功能实现自行决定如何分配处理
     * （如活熔炉每 tick 只处理一个），从根本上避免速度翻倍。
     *
     * @param context 容器上下文
     * @param level 世界
     */
    public static void processContext(ContainerContext context, Level level) {
        Set<String> occupiedSlots = context.getOccupiedSlots();
        if (occupiedSlots != null) {
            occupiedSlots.clear();
        }

        Set<Integer> transferredTargetSlots = context.getTransferredTargetSlots();
        if (transferredTargetSlots != null) {
            transferredTargetSlots.clear();
        }

        int containerSize = context.getSize();
        if (containerSize <= 0) {
            return;
        }

        Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = new LinkedHashMap<>();

        for (int i = 0; i < context.getSize(); i++) {
            ItemStack stack = context.getItem(i);
            if (LivingItemManager.isLivingItem(stack)) {
                var functions = LivingItemManager.getApplicableFunctions(stack);
                for (var function : functions) {
                    grouped.computeIfAbsent(function, k -> new ArrayList<>())
                            .add(new LivingItemFunction.SlotEntry(i, stack));
                }
            }
        }

        for (var entry : grouped.entrySet()) {
            entry.getKey().tick(entry.getValue(), context, level);
        }
    }

    /**
     * 处理区块中的所有方块实体，对含容器的方块实体执行活物品 tick。
     *
     * 去重策略：
     * - 对于大箱子（CompoundContainer），ChestBlock.getContainer() 每次调用都返回新实例，
     *   不能用 Container 对象做 IdentityHashMap 去重
     * - 改用 ChestBlockEntity 身份去重：只要大箱子的任一半边已被处理，就跳过
     * - 对于非箱子容器，仍用 Container 对象去重
     *
     * @param blockEntities 区块中的方块实体集合
     * @param level 世界
     * @param processedContainers 已处理的非箱子容器（用于去重）
     * @param processedChests 已处理的箱子方块实体（用于大箱子去重）
     */
    public static void processBlockEntities(Iterable<BlockEntity> blockEntities, Level level,
                                            IdentityHashMap<Container, Boolean> processedContainers,
                                            IdentityHashMap<ChestBlockEntity, Boolean> processedChests) {
        for (var be : blockEntities) {
            Container container = null;
            List<BlockPos> positions = new ArrayList<>();
            List<BlockEntity> blockEntities2 = new ArrayList<>();

            if (be instanceof ChestBlockEntity chest) {
                if (processedChests.put(chest, Boolean.TRUE) != null) continue;

                BlockPos pos = chest.getBlockPos();
                BlockState state = level.getBlockState(pos);

                blockEntities2.add(chest);
                positions.add(pos);

                if (state.getBlock() instanceof ChestBlock chestBlock) {
                    ChestType chestType = state.getValue(ChestBlock.TYPE);
                    if (chestType != ChestType.SINGLE) {
                        Direction direction = ChestBlock.getConnectedDirection(state);
                        BlockPos otherPos = pos.relative(direction);
                        BlockEntity otherBe = level.getBlockEntity(otherPos);
                        if (otherBe instanceof ChestBlockEntity otherChest) {
                            processedChests.put(otherChest, Boolean.TRUE);
                            blockEntities2.add(otherChest);
                            positions.add(otherPos);
                        }
                    }
                    container = ChestBlock.getContainer(chestBlock, state, level, pos, false);
                }
                if (container == null) {
                    container = chest instanceof Container ? (Container) chest : null;
                }
            } else if (be instanceof Container c) {
                if (processedContainers.put(c, Boolean.TRUE) != null) continue;
                container = c;
                blockEntities2.add(be);
                positions.add(be.getBlockPos());
            }

            if (container != null) {
                processContext(new SimpleContainerContext(container, positions, blockEntities2), level);
            }
        }
    }
}