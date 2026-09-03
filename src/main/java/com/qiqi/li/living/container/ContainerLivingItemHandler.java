package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.util.DoubleChestPositions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.RandomizableContainer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.compat.create.StressOutputManager;
import com.qiqi.li.living.domain.water.LivingWaterBucketFunction;
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

    /**
     * 单一容器数据条目 —— 合并原 6 张同键 Map（流体 / 红石 / 电力 / 修订计数 /
     * 内容签名 / 快照缓存）为一张 {@code Map<String, ContainerEntry>}，消除三套
     * 几乎一字不差的 get-or-create 与 {@code cleanupStalePosIndex} 里重复 4 遍的
     * 「无流体且无红石且无电力」判定。见 {@link #entry} 与 {@link #cleanupStaleData}。
     */
    private static final Map<String, ContainerEntry> CONTAINER_DATA = new HashMap<>();

    /**
     * 位置 → 缓存键 反向索引（键空间与上面不同：PosKey→cacheKey，不是 cacheKey→数据）。
     * 供 mixin 热路径（{@code BlockStateBase.getSignal}、{@code RedStoneWireBlock.getConnectingSide}）
     * 以 O(1) 定位容器数据，替代按坐标正则遍历全表。
     */
    private static final Map<PosKey, String> POS_TO_CACHE_KEY = new HashMap<>();

    /** 每容器聚合数据；惰性字段随首次访问创建，rev/contentSig/snapshot 跨 tick 持久。 */
    private static final class ContainerEntry {
        ContainerFluidData fluid;
        ContainerRedstoneData redstone;
        com.qiqi.li.living.domain.power.ContainerPowerData power;
        long revision;
        int contentSig = Integer.MIN_VALUE;   // 未算过签名时的哨兵
        long cachedSnapshotRevision = -1;      // 与下方快照配对的修订计数
        ContainerSnapshot cachedSnapshot;

        boolean hasData() {
            return fluid != null || redstone != null || power != null;
        }
    }

    private static final int CLEANUP_INTERVAL = 1200;
    private static int cleanupCounter;

    /** 维度 + 坐标，作为位置索引的键（含维度，避免跨维度同坐标冲突） */
    private record PosKey(ResourceKey<Level> dim, BlockPos pos) {}

    /**
     * 构建容器数据的缓存键。
     *
     * <p>在 {@code containerKey} 前拼接维度，避免主世界与下界同坐标的两个容器
     * 共用同一条流体/红石数据。{@code containerKey} 自身格式保持不变，
     * 以兼容 {@code EnderChannelRegistry} 对其的解析。</p>
     *
     * @return 缓存键，容器无 containerKey 时返回 null
     */
    private static String cacheKey(ContainerContext ctx) {
        String key = ctx.getContainerKey();
        if (key == null) return null;
        Level level = ctx.getLevel();
        if (level == null || level.dimension() == null) return key;
        return level.dimension().location() + "|" + key;
    }

    /** 为容器关联的所有方块位置建立反向索引 */
    private static void indexPositions(ContainerContext ctx, String cacheKey) {
        Level level = ctx.getLevel();
        if (level == null) return;
        ResourceKey<Level> dim = level.dimension();
        if (dim == null) return; // 无维度信息（如单元测试 mock）时跳过位置索引
        for (BlockPos pos : ctx.getAssociatedBlockPositions()) {
            POS_TO_CACHE_KEY.put(new PosKey(dim, pos.immutable()), cacheKey);
        }
    }

    /**
     * 获取或创建容器聚合条目（含惰性创建流体/红石/电力数据）。
     * 首次创建时顺便建位置反向索引；context 无 containerKey 时返回 null。
     */
    private static ContainerEntry entry(ContainerContext ctx) {
        String key = cacheKey(ctx);
        if (key == null) return null;
        ContainerEntry e = CONTAINER_DATA.get(key);
        if (e == null) {
            e = new ContainerEntry();
            CONTAINER_DATA.put(key, e);
            indexPositions(ctx, key);
        }
        return e;
    }

    /**
     * 获取或创建容器持久化流体数据。
     * 返回 null 表示容器不支持流体数据（如没有 containerKey）。
     */
    public static ContainerFluidData getFluidData(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return null;
        if (e.fluid != null) return e.fluid;

        if (ctx instanceof SimpleContainerContext simpleCtx) {
            for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                ContainerFluidData persisted = be.getData(LivingItemManager.CONTAINER_FLUID_DATA);
                if (persisted != null && persisted != ContainerFluidData.EMPTY && !persisted.isEmpty()) {
                    e.fluid = persisted;
                    return persisted;
                }
            }
        }

        e.fluid = new ContainerFluidData();
        return e.fluid;
    }

    /**
     * 清理指定位置容器的流体/红石/电力数据（容器方块被破坏时调用）。
     */
    public static void removeDataByPos(Level level, BlockPos pos) {
        String key = POS_TO_CACHE_KEY.remove(new PosKey(level.dimension(), pos.immutable()));
        if (key == null) return;
        CONTAINER_DATA.remove(key);
        POS_TO_CACHE_KEY.values().removeIf(key::equals);
    }

    /**
     * 清理过期的流体/红石/电力数据（超过 120s 未访问的整条容器条目回收）。
     * 仅当条目内<b>所有存在的数据类型</b>都过期才回收，避免误丢仍活跃的单一类型数据。
     */
    private static void cleanupStaleData(long currentTimeMs) {
        CONTAINER_DATA.entrySet().removeIf(en -> {
            ContainerEntry e = en.getValue();
            boolean fluidStale = e.fluid == null || currentTimeMs - e.fluid.getLastTickTime() > 120_000;
            boolean redstoneStale = e.redstone == null
                || currentTimeMs - e.redstone.getLastTickTime() > 120_000;
            boolean powerStale = e.power == null
                || currentTimeMs - e.power.getLastTickTime() > 120_000;
            return fluidStale && redstoneStale && powerStale;
        });
    }

    /**
     * 获取或创建容器持久化红石数据。
     * 返回 null 表示容器不支持红石数据（如没有 containerKey）。
     */
    public static ContainerRedstoneData getRedstoneData(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return null;
        if (e.redstone == null) e.redstone = new ContainerRedstoneData();
        return e.redstone;
    }

    /**
     * 获取或创建容器持久化红电数据（电力层账本）。
     * 返回 null 表示容器不支持（如没有 containerKey）。
     */
    public static com.qiqi.li.living.domain.power.ContainerPowerData getPowerData(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return null;
        if (e.power == null) {
            e.power = new com.qiqi.li.living.domain.power.ContainerPowerData();
        }
        return e.power;
    }

    /** 按位置 O(1) 查询红石数据，供 mixin 热路径调用 */
    public static ContainerRedstoneData getRedstoneDataByPos(Level level, BlockPos pos) {
        String key = POS_TO_CACHE_KEY.get(new PosKey(level.dimension(), pos));
        if (key == null) return null;
        ContainerEntry e = CONTAINER_DATA.get(key);
        return e == null ? null : e.redstone;
    }

    /** 按位置 O(1) 查询红电数据（电力层，供对外能量接口调用） */
    public static com.qiqi.li.living.domain.power.ContainerPowerData getPowerDataByPos(Level level, BlockPos pos) {
        String key = POS_TO_CACHE_KEY.get(new PosKey(level.dimension(), pos));
        if (key == null) return null;
        ContainerEntry e = CONTAINER_DATA.get(key);
        return e == null ? null : e.power;
    }

    /**
     * 读取容器当前修订计数（物品内容版本号）。无 containerKey 的上下文返回 0。
     */
    public static long getContainerRevision(ContainerContext ctx) {
        String key = cacheKey(ctx);
        if (key == null) return 0L;
        ContainerEntry e = CONTAINER_DATA.get(key);
        return e == null ? 0L : e.revision;
    }

    /**
     * 自增容器修订计数。物品被 {@code setItem} 替换、或经 {@code syncSlotToClients}
     * 就地修改 DataComponent（方向配置、状态等）后调用，使快照缓存失效。
     */
    public static void bumpContainerRevision(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return;
        e.revision++;
    }

    /**
     * 用内容签名兜底容器修订计数：内容变化则 bump，使稳态跳过与快照缓存失效。
     *
     * <p>必须在每 tick 的槽位扫描之后、{@code TickContext} 创建之前调用，
     * 这样本 tick 就能看到最新的物品与重算结果。开销 O(槽位数)。</p>
     *
     * <p>只比对「物品 id + 数量」：自定义 DataComponent 的变更只可能由本模组发起，
     * 而那些路径已经走了 {@code syncSlotToClients}（会 bump），无需在此重复覆盖。</p>
     */
    public static void syncContentRevision(ContainerContext ctx) {
        ContainerEntry e = entry(ctx);
        if (e == null) return;
        int sig = computeContentSignature(ctx);
        int prev = e.contentSig;
        e.contentSig = sig;
        if (prev == Integer.MIN_VALUE || prev != sig) {
            bumpContainerRevision(ctx);
        }
    }

    /** 容器内容签名：逐槽位混入 物品 id 与数量（空槽参与混合，保证槽位移动也能检出） */
    private static int computeContentSignature(ContainerContext ctx) {
        int h = 1;
        int size = ctx.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack s = ctx.getItem(i);
            if (s == null || s.isEmpty()) {
                h = h * 31;
            } else {
                h = h * 31 + Item.getId(s.getItem()) * 31 + s.getCount();
            }
        }
        return h;
    }

    /**
     * 取（或构建并缓存）容器快照。仅当修订计数相对上次构建发生变化时才重建，
     * 否则复用跨 tick 缓存的同一快照，避免每个 tick 重复扫描全部物品。
     *
     * <p>快照内的流体数据持有跨 tick 持久对象引用，其字段被就地更新，
     * 因此即便快照按修订计数缓存，流体状态仍反映当前值。</p>
     */
    public static ContainerSnapshot getCachedSnapshot(ContainerContext ctx, long revision,
                                                      ContainerFluidData fluidData, TickContext tick) {
        ContainerEntry e = entry(ctx);
        if (e == null) return ContainerSnapshot.capture(ctx, tick, fluidData);
        if (e.cachedSnapshot != null && e.cachedSnapshotRevision == revision) return e.cachedSnapshot;
        ContainerSnapshot snap = ContainerSnapshot.capture(ctx, tick, fluidData);
        e.cachedSnapshotRevision = revision;
        e.cachedSnapshot = snap;
        return snap;
    }

    /**
     * 清理已失去主缓存条目的位置索引。
     * 仅当对应 cacheKey 在 {@link #CONTAINER_DATA} 中不存在或条目内已无任何子数据时，
     * 才回收其位置反向索引（快照/修订计数/内容签名随条目一起被 {@link #cleanupStaleData} 回收）。
     */
    private static void cleanupStalePosIndex() {
        POS_TO_CACHE_KEY.values().removeIf(
            key -> {
                ContainerEntry e = CONTAINER_DATA.get(key);
                return e == null || !e.hasData();
            });
    }

    /** 清空全部容器级缓存（服务端关闭时调用，避免跨存档残留） */
    public static void clearAllCaches() {
        CONTAINER_DATA.clear();
        POS_TO_CACHE_KEY.clear();
        LivingWaterBucketFunction.clearAllCaches();
        cleanupCounter = 0;
    }

    private static void updateStressOutput(SimpleContainerContext ctx, BlockEntity containerBE,
                                            ContainerStressData stressData) {
        StressOutputManager.apply(containerBE.getLevel(), containerBE.getBlockPos(), stressData);
    }

    private static void updatePlayerFeetStressOutput(SimpleContainerContext ctx,
                                                      ContainerStressData stressData) {
        Inventory inventory = ctx.getInventory();
        if (inventory == null) return;
        Player player = inventory.player;
        Level level = player.level();
        if (level.isClientSide) return;

        BlockPos feetPos = player.blockPosition();
        StressOutputManager.apply(level, feetPos, stressData);
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

        String monitorKey = context.getContainerKey();
        com.qiqi.li.living.debug.ContainerMonitor.beforeProcess(monitorKey, context);

        // 阶段 1：扫描容器全部槽位，将活物品按功能分组，同时校验内容签名
        Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped = scanAndGroupLivingItems(context);
        syncContentRevision(context);

        // 空容器：仅残留红石归零
        if (grouped.isEmpty()) {
            handleEmptyContainer(context, startNanos, monitorKey);
            return;
        }

        // 准备 TickContext（功能 tick 与环境交互的上下文）
        TickContext tick = new TickContext(context);
        if (context instanceof SimpleContainerContext simpleCtx) {
            simpleCtx.setTickContext(tick);
        }

        long scanEndNanos = System.nanoTime();
        PerfMetrics.recordPhase("scan", scanEndNanos - startNanos);

        long stressEndNanos;
        try {
            // 阶段 2：功能 tick（按优先级执行各功能的 tick 逻辑）
            tickFunctionSlots(grouped, tick);
            runFunctionTicks(grouped, context, tick, level);

            long funcTickEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("func_tick", funcTickEndNanos - scanEndNanos);

            // 阶段 3：EnderChannel 脏通道刷新 + 水桶冗余流清理
            flushEnderChannels(tick, grouped);

            long flushChEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("flush_channels", flushChEndNanos - funcTickEndNanos);

            // 阶段 4：容器级数据（红石、流体等按优先级传播）
            runContainerDataTicks(grouped, context, tick);

            long containerDataEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("container_data", containerDataEndNanos - flushChEndNanos);

            // 阶段 4.5：刷新运行时数据缓存到客户端（用于 tooltip 展示，不影响物品堆叠）
            if (context instanceof SimpleContainerContext simpleCtx) {
                java.util.List<Container> containers = new java.util.ArrayList<>();
                for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                    if (be instanceof Container c) {
                        containers.add(c);
                    }
                }
                if (!containers.isEmpty()) {
                    ContainerRuntimeCache.flushToClients(level, containers);
                }
            }

            // 阶段 5：写回 BlockEntity（应力 + 流体）与过期清理
            writebackBlockEntities(context, tick);
            incrementCleanup();

            stressEndNanos = System.nanoTime();
            PerfMetrics.recordPhase("stress", stressEndNanos - containerDataEndNanos);
        } finally {
            // 无论功能 tick / 容器级数据 / 写回是否抛异常，都同步脏槽到客户端并清掉 stale
            // TickContext：否则异常时客户端物品显示错位，且下一 tick 残留旧上下文（P1-6）。
            if (context instanceof SimpleContainerContext simpleCtx) {
                simpleCtx.flushDirtySlots();
                simpleCtx.setTickContext(null);
            }
        }

        // 阶段 6：脏槽刷新（from finally）后的 perf 收尾
        long flushSlotsEndNanos = System.nanoTime();
        PerfMetrics.recordPhase("flush_slots", flushSlotsEndNanos - stressEndNanos);

        PerfMetrics.recordTick(flushSlotsEndNanos - startNanos);

        com.qiqi.li.living.debug.ContainerMonitor.afterProcess(monitorKey, context);

        if (PerfMetrics.shouldReport()) {
            PerfMetrics.printReport();
        }
    }

    /**
     * 容器内无活物品时，仅需让残留红石信号归零。
     * 若容器有红石数据且已计算过，再跑一次 {@link ContainerRedstoneData#calculate} 使其归零。
     */
    private static void handleEmptyContainer(ContainerContext context, long startNanos, String monitorKey) {
        if (context instanceof SimpleContainerContext simpleCtx) {
            String key = cacheKey(simpleCtx);
            ContainerRedstoneData rd = null;
            if (key != null) {
                ContainerEntry re = CONTAINER_DATA.get(key);
                rd = re == null ? null : re.redstone;
            }
            if (rd != null) {
                TickContext tick = new TickContext(context);
                simpleCtx.setTickContext(tick);
                try {
                    rd.calculate(context, tick);
                } finally {
                    simpleCtx.flushDirtySlots();
                    simpleCtx.setTickContext(null);
                }
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        PerfMetrics.recordPhase("scan", elapsedNanos);
        PerfMetrics.recordTick(elapsedNanos);
        if (PerfMetrics.shouldReport()) {
            PerfMetrics.printReport();
        }
    }

    /**
     * 将扫描结果的功能槽位集合写入 TickContext，并记录 PerfMetrics 统计。
     */
    private static void tickFunctionSlots(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            TickContext tick) {
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
    }

    /**
     * 执行各功能的 tick 逻辑（按优先级）。
     * 每个功能只调用一次 tick，传入其管理的所有活物品槽位。
     */
    private static void runFunctionTicks(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            ContainerContext context, TickContext tick, Level level) {
        for (var entry : grouped.entrySet()) {
            entry.getKey().tick(entry.getValue(), context, tick, level);
        }
    }

    /**
     * 刷新 EnderChannel 脏通道，并清理无活水桶容器中的冗余流数据。
     */
    private static void flushEnderChannels(TickContext tick,
                                            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped) {
        EnderChannelRegistry.getInstance().flushDirtyChannels();

        // 若容器内没有活水桶，清除流体数据中的冗余流（避免残留流一直动画）
        boolean hasWaterBucket = false;
        for (var entry : grouped.entrySet()) {
            if ("living_water_bucket".equals(entry.getKey().getFunctionId())) {
                hasWaterBucket = true;
                break;
            }
        }
        if (!hasWaterBucket && tick.fluidData != null && tick.fluidData != ContainerFluidData.EMPTY) {
            tick.fluidData.getFlows().clear();
        }
    }

    /**
     * 将应力与流体数据写回 BlockEntity（或玩家脚底），并清理空流体缓存。
     */
    private static void writebackBlockEntities(ContainerContext context, TickContext tick) {
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
        if (fluidData != null && !fluidData.isEmpty()) {
            if (context instanceof SimpleContainerContext simpleCtx) {
                for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                    be.setData(LivingItemManager.CONTAINER_FLUID_DATA.value(), fluidData);
                }
            }
        }
        if (fluidData != null && fluidData.isEmpty()) {
            String fluidKey = cacheKey(context);
            if (fluidKey != null) {
                ContainerEntry fe = CONTAINER_DATA.get(fluidKey);
                if (fe != null) fe.fluid = null;
            }
        }
    }

    /**
     * 递增清理计数器，达到间隔时执行过期数据清理。
     */
    private static void incrementCleanup() {
        cleanupCounter++;
        if (cleanupCounter >= CLEANUP_INTERVAL) {
            cleanupCounter = 0;
            long currentTimeMs = System.currentTimeMillis();
            cleanupStaleData(currentTimeMs);
            cleanupStalePosIndex();
            LivingWaterBucketFunction.cleanupStaleEntries(currentTimeMs);
        }
    }

    /**
     * 扫描容器全部槽位，将活物品按功能分组收集。
     *
     * <p>两阶段设计的「扫描」阶段：先按功能分组，再对每种功能只调用一次 tick，
     * 从根本上避免 N 个活物品 = N 倍速度（如活熔炉每 tick 只处理一个）。</p>
     */
    private static Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> scanAndGroupLivingItems(
            ContainerContext context) {
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
        return grouped;
    }

    /**
     * 对拥有容器级数据（{@link HasContainerData}）的功能按优先级排序后逐个 tick。
     * 优先级高的先跑，确保依赖顺序（如活红石需先于下游消费状态）。
     */
    private static void runContainerDataTicks(
            Map<LivingItemFunction, List<LivingItemFunction.SlotEntry>> grouped,
            ContainerContext context, TickContext tick) {
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
                if (halfBe != null) {
                    if (halfBe instanceof RandomizableContainer rc && rc.getLootTable() != null) {
                        return false;
                    }
                    blockEntities.add(halfBe);
                }
            }
        } else {
            positions.add(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) {
                    return false;
                }
                blockEntities.add(be);
            }
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
     * <p>委托给 {@link DoubleChestPositions#find}，原实现已提取至该工具类。</p>
     */
    public static List<BlockPos> findDoubleChestPositions(Level level, BlockPos pos) {
        return DoubleChestPositions.find(level, pos);
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