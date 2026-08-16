package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.compat.create.ModCreate;
import com.qiqi.li.living.perf.PerfMetrics;

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

    private static final Map<String, ContainerFluidData> FLUID_DATA_CACHE = new LinkedHashMap<>();
    private static final int CLEANUP_INTERVAL = 1200;
    private static int cleanupCounter;

    /**
     * 获取或创建容器持久化流体数据。
     * 返回 null 表示容器不支持流体数据（如没有 containerKey）。
     */
    public static ContainerFluidData getFluidData(String containerKey) {
        if (containerKey == null) return null;
        return FLUID_DATA_CACHE.computeIfAbsent(containerKey, k -> new ContainerFluidData());
    }

    /**
     * 清理指定容器的流体数据（容器销毁时调用）。
     */
    public static void removeFluidData(String containerKey) {
        if (containerKey != null) {
            FLUID_DATA_CACHE.remove(containerKey);
        }
    }

    /**
     * 清理过期的流体数据（超过 STALE_THRESHOLD 毫秒未访问的条目）。
     */
    private static void cleanupStaleFluidData(long currentTimeMs) {
        FLUID_DATA_CACHE.entrySet().removeIf(entry -> {
            ContainerFluidData data = entry.getValue();
            return currentTimeMs - data.getLastTickTime() > 120_000;
        });
    }

    private static void updateStressOutput(SimpleContainerContext ctx, BlockEntity containerBE,
                                            ContainerStressData stressData) {
        ModCreate.updateStressOutput(containerBE.getLevel(), containerBE.getBlockPos(), stressData);
    }

    private static void updatePlayerFeetStressOutput(SimpleContainerContext ctx,
                                                      ContainerStressData stressData) {
        Inventory inventory = ctx.getInventory();
        if (inventory == null) return;
        Player player = inventory.player;
        Level level = player.level();
        if (level.isClientSide) return;

        BlockPos feetPos = player.blockPosition();
        ModCreate.updateStressOutput(level, feetPos, stressData);
    }

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

        processEnderChest(inventory.player, level);
    }

    /**
     * 处理玩家末影箱中的所有活物品。
     *
     * <p>末影箱容器（{@link PlayerEnderChestContainer}）既不是方块实体也不是玩家背包的一部分，
     * 需要单独处理。使用 "player_&lt;uuid&gt;_ender_chest" 作为容器 key，
     * 与玩家背包的 key（"player_&lt;uuid&gt;"）区分。</p>
     *
     * @param player 玩家
     * @param level 世界
     */
    public static void processEnderChest(Player player, Level level) {
        if (level.isClientSide) return;
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        if (enderChest == null) return;

        IItemHandler handler = new InvWrapper(enderChest);
        ContainerContext context = new EnderChestContainerContext(handler, player, level);
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
        long startNanos = System.nanoTime();

        int containerSize = context.getSize();
        if (containerSize <= 0) {
            return;
        }

        // 从对象池获取 TickContext（复用减少 GC 压力）
        TickContext tick = TickContext.acquire(context);

        if (context instanceof SimpleContainerContext simpleCtx) {
            simpleCtx.setTickContext(tick);
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

        if (grouped.isEmpty()) {
            if (context instanceof SimpleContainerContext simpleCtx) {
                simpleCtx.setTickContext(null);
            }
            tick.release();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            PerfMetrics.recordTick(elapsedMs);
            if (PerfMetrics.shouldReport()) {
                PerfMetrics.printReport();
            }
            return;
        }

        Map<String, Set<Integer>> functionSlots = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            Set<Integer> slots = new HashSet<>();
            for (var slotEntry : entry.getValue()) {
                slots.add(slotEntry.slotIndex());
            }
            functionSlots.put(entry.getKey().getFunctionId(), slots);
        }
        tick.setFunctionSlots(functionSlots);

        for (var entry : grouped.entrySet()) {
            PerfMetrics.addLivingItem(entry.getKey().getFunctionId(), entry.getValue().size());
            PerfMetrics.recordFunctionCall(entry.getKey().getFunctionId());
        }

        for (var entry : grouped.entrySet()) {
            entry.getKey().tick(entry.getValue(), context, tick, level);
        }

        EnderChannelRegistry.getInstance().flushDirtyChannels();

        List<Map.Entry<LivingItemFunction, List<LivingItemFunction.SlotEntry>>> hcdEntries = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            if (entry.getKey() instanceof HasContainerData) {
                hcdEntries.add(entry);
            }
        }
        hcdEntries.sort(Comparator.comparingInt(e -> ((HasContainerData) e.getKey()).getPriority()));

        for (var entry : hcdEntries) {
            ((HasContainerData) entry.getKey()).tickContainerData(entry.getValue(), context, tick);
        }

        ContainerStressData stressData = tick.stressData;
        if (stressData != null && context instanceof SimpleContainerContext simpleCtx) {
            for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                be.setData(LivingItemManager.CONTAINER_STRESS_DATA.value(), stressData);
                updateStressOutput(simpleCtx, be, stressData);
            }

            if (simpleCtx.getAssociatedBlockEntities().isEmpty() && simpleCtx.getInventory() != null) {
                updatePlayerFeetStressOutput(simpleCtx, stressData);
            }
        }

        ContainerFluidData fluidData = tick.fluidData;
        String containerKey = context.getContainerKey();
        if (fluidData != null && fluidData.isEmpty() && containerKey != null) {
            FLUID_DATA_CACHE.remove(containerKey);
        }

        cleanupCounter++;
        if (cleanupCounter >= CLEANUP_INTERVAL) {
            cleanupCounter = 0;
            cleanupStaleFluidData(System.currentTimeMillis());
        }

        if (context instanceof SimpleContainerContext simpleCtx2) {
            simpleCtx2.flushDirtySlots();
            simpleCtx2.setTickContext(null);
        }

        tick.release();

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 记录 tick 耗时
        PerfMetrics.recordTick(elapsedMs);

        // 检查是否需要打印报告
        if (PerfMetrics.shouldReport()) {
            PerfMetrics.printReport();
        }
    }

    /**
     * 处理指定位置的容器方块实体，构建上下文并执行活物品 tick。
     *
     * <p>此方法是 {@link #processBlockEntities} 和
     * {@link com.qiqi.li.LivingItem#processLevelContainers} 的公共逻辑提取，
     * 避免两个调用点重复编写"获取 handler → 大箱子检测 → 构建上下文 →
     * 活跃标记 → 处理"的流程。</p>
     *
     * @param level 世界
     * @param pos 容器方块位置
     * @param handler 已获取的 IItemHandler（如果为 null 则从能力系统获取）
     * @param processedHandlers 已处理的 IItemHandler 去重集合
     * @param processedKeys 已处理的 containerKey 去重集合
     * @return 是否成功处理了该容器
     */
    public static boolean processContainerAt(Level level, BlockPos pos, IItemHandler handler,
                                              IdentityHashMap<IItemHandler, Boolean> processedHandlers,
                                              Set<String> processedKeys) {
        if (handler == null) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        }
        if (handler == null) return false;
        if (processedHandlers != null && processedHandlers.put(handler, Boolean.TRUE) != null) return false;

        List<BlockPos> positions = new ArrayList<>();
        List<BlockEntity> blockEntities = new ArrayList<>();

        List<BlockPos> doubleChestPos = findDoubleChestPositions(level, pos);
        if (!doubleChestPos.isEmpty()) {
            for (BlockPos cp : doubleChestPos) {
                positions.add(cp);
                BlockEntity halfBe = level.getBlockEntity(cp);
                if (halfBe != null) blockEntities.add(halfBe);
            }
        } else {
            positions.add(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) blockEntities.add(be);
        }

        ContainerContext context = new SimpleContainerContext(handler, null, positions, blockEntities, level);
        String key = context.getContainerKey();
        if (processedKeys != null && !processedKeys.add(key)) return false;

        processContext(context, level);
        return true;
    }

    /**
     * 处理区块中的所有方块实体，对含容器的方块实体执行活物品 tick。
     *
     * 去重策略（双重保障）：
     * 1. IdentityHashMap 按 IItemHandler 实例去重（NeoForge 大箱子可能返回同一实例）
     * 2. HashSet 按 containerKey 去重（防止 IItemHandler 每次创建新实例时重复处理）
     *
     * 惰性 Tick 优化：
     * 扫描容器时，如果发现活物品则标记容器为活跃，
     * 如果没有活物品则移除活跃标记。
     *
     * @param blockEntities 区块中的方块实体集合
     * @param level 世界
     * @param processedHandlers 已处理的 IItemHandler（用于去重）
     */
    public static void processBlockEntities(Iterable<BlockEntity> blockEntities, Level level,
                                            IdentityHashMap<IItemHandler, Boolean> processedHandlers) {
        Set<String> processedKeys = new HashSet<>();

        for (var be : blockEntities) {
            processContainerAt(level, be.getBlockPos(), null, processedHandlers, processedKeys);
        }
    }

    /**
     * 检测指定位置是否是大箱子，返回左右两半的位置（LEFT 在前，RIGHT 在后）。
     * 如果不是大箱子，返回空列表。
     *
     * <p>此方法为 public static，供 {@link com.qiqi.li.LivingItem#processLevelContainers}
     * 在活跃容器路径中复用，避免代码重复。</p>
     */
    public static List<BlockPos> findDoubleChestPositions(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return List.of();

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) return List.of();

        Direction connectedDir = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connectedDir);

        if (chestType == ChestType.LEFT) {
            return List.of(pos, otherPos);
        } else {
            return List.of(otherPos, pos);
        }
    }

    private static class EnderChestContainerContext extends SimpleContainerContext {
        private final String enderChestKey;

        EnderChestContainerContext(IItemHandler handler, Player player, Level level) {
            super(handler, null, new ArrayList<>(), new ArrayList<>(), level);
            this.enderChestKey = "player_" + player.getStringUUID() + "_ender_chest";
        }

        @Override
        public String getContainerKey() {
            return enderChestKey;
        }
    }
}