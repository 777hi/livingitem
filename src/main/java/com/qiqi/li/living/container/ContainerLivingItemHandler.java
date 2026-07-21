package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.qiqi.li.living.LivingItemFunction;
import com.qiqi.li.living.LivingItemManager;

/**
 * 活物品容器处理器 —— 负责遍历容器中的物品并执行活物品 tick。
 *
 * 职责：
 * 1. 将不同类型的容器统一转换为 {@link ContainerContext}
 * 2. 遍历容器中所有物品，对活物品执行已注册功能的 tick 逻辑
 * 3. 处理大箱子的去重（避免左右两半被分别处理导致速度翻倍）
 *
 * 容器类型与 Context 构建策略：
 * - 玩家背包（Inventory）：直接包装为 SimpleContainerContext
 * - 方块容器（箱子等）：通过 IItemHandler 能力获取并包装
 * - 大箱子：NeoForge 为左右两半返回同一个 IItemHandler 实例，天然去重
 */
public class ContainerLivingItemHandler {
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 处理玩家背包中的所有活物品。
     *
     * @param inventory 玩家背包
     * @param level 世界
     */
    public static void processContainer(Inventory inventory, Level level) {
        if (level.isClientSide) return;
        IItemHandler handler = inventory.player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (handler == null) return;
        ContainerContext context = buildContext(handler, inventory, level);
        processContext(context, level);
    }

    /**
     * 根据容器类型构建对应的 ContainerContext。
     *
     * @param handler IItemHandler
     * @param inventory 玩家背包（nullable，仅玩家背包场景传入）
     * @param level 世界
     * @return 包装后的容器上下文
     */
    public static ContainerContext buildContext(IItemHandler handler, Inventory inventory, Level level) {
        return new SimpleContainerContext(handler, inventory, new ArrayList<>(), new ArrayList<>(), level);
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

        // 构建容器快照：每 tick 扫描一次，供所有组件复用
        context.setSnapshot(ContainerSnapshot.capture(context));

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
     * 统一通过 NeoForge IItemHandler 能力检测容器，无需区分原版方块或模组方块。
     * 去重策略：使用 IdentityHashMap 按 IItemHandler 实例去重。
     * 对于大箱子，NeoForge 为左右两半返回同一个 IItemHandler 实例，天然去重。
     *
     * @param blockEntities 区块中的方块实体集合
     * @param level 世界
     * @param processedHandlers 已处理的 IItemHandler（用于去重）
     */
    public static void processBlockEntities(Iterable<BlockEntity> blockEntities, Level level,
                                            IdentityHashMap<IItemHandler, Boolean> processedHandlers) {
        for (var be : blockEntities) {
            IItemHandler itemHandler = level.getCapability(
                Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
            if (itemHandler == null) continue;
            if (processedHandlers.put(itemHandler, Boolean.TRUE) != null) continue;

            List<BlockPos> positions = new ArrayList<>();
            List<BlockEntity> blockEntities2 = new ArrayList<>();
            positions.add(be.getBlockPos());
            blockEntities2.add(be);

            processContext(new SimpleContainerContext(itemHandler, null, positions, blockEntities2, level), level);
        }
    }
}