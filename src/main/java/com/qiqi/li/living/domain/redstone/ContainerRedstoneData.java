package com.qiqi.li.living.domain.redstone;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.hopper.CrossContainerTransfer;
import com.qiqi.li.living.model.Pos2D;

public class ContainerRedstoneData {

    // ── 槽位类型位掩码 ──
    // 传播热路径上每格要做十几次「邻居是什么元件」的判定。
    // 用 Set<Integer>.contains 会带来装箱 + 哈希查找，改为按槽位索引的位图。
    // EDGE_* 常量公开供电力层（domain/power）使用。
    public static final int EDGE_UP = 0;
    public static final int EDGE_DOWN = 1;
    public static final int EDGE_LEFT = 2;
    public static final int EDGE_RIGHT = 3;
    private static final int E_UP = EDGE_UP;
    private static final int E_DOWN = EDGE_DOWN;
    private static final int E_LEFT = EDGE_LEFT;
    private static final int E_RIGHT = EDGE_RIGHT;
    static final int BIT_DUST = 1;
    static final int BIT_TORCH = 1 << 1;
    static final int BIT_BUTTON = 1 << 2;
    static final int BIT_LEVER = 1 << 3;
    static final int BIT_LAMP = 1 << 4;
    static final int BIT_REPEATER = 1 << 5;
    static final int BIT_COMPARATOR = 1 << 6;
    static final int BIT_BLOCK = 1 << 7;
    static final int BIT_COPPER = 1 << 8;
    static final int BIT_CHISELED = 1 << 9;
    static final int BIT_CUT = 1 << 10;
    static final int BIT_GRATE = 1 << 11;
    static final int BIT_BULB = 1 << 12;

    /** 所有红石元件（不含红石灯）——导电方块供电时需跳过这些槽位 */
    private static final int MASK_REDSTONE = BIT_DUST | BIT_TORCH | BIT_BUTTON
        | BIT_LEVER | BIT_REPEATER | BIT_COMPARATOR | BIT_BLOCK | BIT_COPPER;

    /** 每个槽位的元件类型位图，每次 calculate 开头从快照重建 */
    private int[] slotMask = new int[0];

    /** 本 tick 容器快照（位图与氧化等级来源），calculate 开头绑定 */
    private ContainerSnapshot currentSnapshot;
    private ContainerContext currentCtx;

    private EdgeGrid edgeGrid;
    private EdgeGrid prevEdgeGrid;
    private final int[] faceInput = new int[4];
    private final int[] faceOutput = new int[4];
    private final int[] prevFaceOutput = new int[4];

    private boolean processedThisTick;

    /** 稳态跳过状态（跨 tick 持久），封装在不可变值对象中 */
    private SteadyState steady = SteadyState.initial();

    /** 稳态跳过次数（性能观测 / 测试可见），与 {@code steady.skipCount()} 同步 */
    public int steadySkipCount = 0;

    public ContainerRedstoneData() {
        this.processedThisTick = false;
    }

    public long getLastTickTime() {
        return steady.lastTickTime();
    }

    public void resetProcessedFlag() {
        this.processedThisTick = false;
    }

    public static int getSignalCap(int stackCount) {
        return stackCount == 1 ? 15 : stackCount * stackCount;
    }

    /** 边方向数（电力层逐方向采样用，见 domain/power） */
    public static final int EDGE_COUNT = 4;

    /** 读取该槽位指定方向的当前边信号（电力层事件采样用） */
    public int getEdgeValue(int slot, int dir) {
        return edgeGrid == null ? 0 : edgeGrid.get(slot, dir);
    }

    /** 填充该槽位四个方向的当前边信号（out 长度 ≥ {@link #EDGE_COUNT}） */
    public void getEdgeValues(int slot, int[] out) {
        for (int dir = 0; dir < EDGE_COUNT && dir < out.length; dir++) {
            out[dir] = edgeGrid == null ? 0 : edgeGrid.get(slot, dir);
        }
    }

    /** 读取该槽位指定方向的上一 tick 边信号 */
    public int getPrevEdgeValue(int slot, int dir) {
        return prevEdgeGrid == null ? 0 : prevEdgeGrid.get(slot, dir);
    }

    // ── 测试专用 seam：直接注入边信号，绕过完整传播 ──
    // 供电力层单测（NetworkTraversalTest）构造振荡器上升沿，直接驱动
    // LivingWaxedCopperFunction.tickContainerData 以验证「按网络组件遍历」重构。
    // 默认网格 9×1（与 size=9 的测试容器匹配）；若 edgeGrid 已被真实传播创建则沿用其尺寸。

    /** 测试专用：写入某槽某方向的当前 tick 边信号 */
    public void setEdgeForTest(int slot, int dir, int value) {
        ensureTestGrid();
        edgeGrid.set(slot, dir, value);
    }

    /** 测试专用：写入某槽某方向的上一 tick 边信号（用于制造上升沿 delta>0） */
    public void setPrevEdgeForTest(int slot, int dir, int value) {
        ensureTestGrid();
        prevEdgeGrid.set(slot, dir, value);
    }

    private void ensureTestGrid() {
        if (edgeGrid == null) {
            edgeGrid = new EdgeGrid(9, 1);
            prevEdgeGrid = new EdgeGrid(9, 1);
        }
    }

    public int getSignal(int slot) {
        if (edgeGrid == null) return 0;
        return edgeGrid.maxOfSlot(slot);
    }

    public int getSlotSignal(int slot, int size, int width) {
        if (edgeGrid == null) return 0;
        int height = (size + width - 1) / width;

        int maxSignal = edgeGrid.maxOfSlot(slot);

        int r = slot / width;
        int c = slot % width;

        if (r == 0) maxSignal = Math.max(maxSignal, faceInput[E_UP]);
        if (r == height - 1) maxSignal = Math.max(maxSignal, faceInput[E_DOWN]);
        if (c == 0) maxSignal = Math.max(maxSignal, faceInput[E_LEFT]);
        if (c == width - 1) maxSignal = Math.max(maxSignal, faceInput[E_RIGHT]);

        return maxSignal;
    }

    private void reset() {
        EdgeGrid temp = prevEdgeGrid;
        prevEdgeGrid = edgeGrid;
        edgeGrid = temp;
        edgeGrid.zero();
        // 注意：faceInput 的清零已移入 injectExternalInputs（calculate 开头提前采样外部输入时用），
        // 此处不再清零，避免提前采样得到的 faceInput 在传播前被冲掉。
    }

    /**
     * 从容器快照重建槽位类型位图。物品未变更时快照被框架跨 tick 缓存，
     * 本方法仅做 O(size) 的数组拷贝，不再每 tick 扫描物品。
     */
    private void buildSlotMask(ContainerSnapshot snap, int size) {
        if (slotMask.length != size) {
            slotMask = new int[size];
        } else {
            Arrays.fill(slotMask, 0);
        }
        int[] mask = snap.getRedstoneMaskOf();
        for (int s = 0; s < size && s < mask.length; s++) {
            slotMask[s] = mask[s];
        }
    }

    private Set<Integer> copperSubset(Set<Integer> copperSlots, ContainerContext context,
            java.util.function.Predicate<net.minecraft.world.item.Item> predicate) {
        Set<Integer> result = new java.util.HashSet<>();
        for (int slot : copperSlots) {
            ItemStack stack = context.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack.getItem())) {
                result.add(slot);
            }
        }
        return result;
    }

    public void calculate(ContainerContext context, TickContext tick) {
        if (processedThisTick) return;
        processedThisTick = true;

        int size = context.getSize();
        int width = context.getWidth();
        int height = (size + width - 1) / width;

        // ── 稳态跳过判定（放最前，尽量早返回省开销）──
        // 先采样外部输入（便宜：邻居世界信号 + 相邻活容器边界信号）算签名；
        // 物品修订计数与外部签名都不变、且无在途倒计时定时器时，本 tick 传播结果与上 tick
        // 完全一致，可直接复用 edgeGrid 跳过整段 calculate（含 BFS）。
        // 安全性：物品变更会 bump 修订计数（rev 变），邻居/原版红石变化会改变外部输入签名；
        // 唯一不受这两者驱动的逐 tick 演化是中继器 delayTimer / 按钮 pulseTimer 倒计时，
        // 故用 hadActiveTimers 作保险——只要有倒计时在跑就强制重算，绝不冻结时序。
        long rev = ContainerLivingItemHandler.getContainerRevision(context);
        injectExternalInputs(context);
        int externalSig = java.util.Arrays.hashCode(faceInput);

        if (steady.canSkip(rev, externalSig, steady.hadActiveTimers())) {
            steady = steady.withSkip();
            steadySkipCount = steady.skipCount();
            return;
        }

        // 取（或构建）本 tick 容器快照；物品未变更时框架返回跨 tick 缓存的同一实例。
        // 红电位图与铜氧化等级均来自快照，calculate 内不再扫描物品。
        this.currentCtx = context;
        this.currentSnapshot = tick.getSnapshot();

        Set<Integer> torchSlots = tick.getFunctionSlots(LivingRedstoneTorchFunction.ID);
        Set<Integer> dustSlots = tick.getFunctionSlots(LivingRedstoneFunction.ID);
        Set<Integer> buttonSlots = tick.getFunctionSlots(LivingButtonFunction.ID);
        Set<Integer> leverSlots = tick.getFunctionSlots(LivingLeverFunction.ID);
        Set<Integer> lampSlots = tick.getFunctionSlots(LivingRedstoneLampFunction.ID);
        Set<Integer> repeaterSlots = tick.getFunctionSlots(LivingRepeaterFunction.ID);
        Set<Integer> comparatorSlots = tick.getFunctionSlots(LivingComparatorFunction.ID);
        Set<Integer> redstoneBlockSlots = tick.getFunctionSlots(LivingRedstoneBlockFunction.ID);
        Set<Integer> copperSlots = tick.getFunctionSlots(LivingCopperFunction.ID);

        boolean hasAny = !torchSlots.isEmpty() || !dustSlots.isEmpty()
            || !buttonSlots.isEmpty() || !leverSlots.isEmpty() || !lampSlots.isEmpty()
            || !repeaterSlots.isEmpty() || !comparatorSlots.isEmpty()
            || !redstoneBlockSlots.isEmpty() || !copperSlots.isEmpty();
        if (edgeGrid == null || edgeGrid.width != width || edgeGrid.height != height) {
            edgeGrid = new EdgeGrid(width, height);
            prevEdgeGrid = new EdgeGrid(width, height);
        }

        if (!hasAny) {
            if (edgeGrid != null) edgeGrid.zero();
            computeFaceOutput(width, height);
            notifyBoundaryChange(context, width, height);
            steady = steady.withRecord(ContainerLivingItemHandler.getContainerRevision(context), externalSig, false);
            steadySkipCount = steady.skipCount();
            return;
        }

        // 传播节拍对齐全局游戏时钟：每 game tick 传播一次。
        // 元件延迟（中继器 delayTimer、按钮 pulseTimer）统一以 game tick 计数，
        // 中继器充能时按 TICKS_PER_REPEATER_STEP 换算档位，保持原版红石刻语义。

        Set<Integer> grateSlots = copperSubset(copperSlots, context, LivingCopperFunction::isGrate);
        Set<Integer> bulbSlots = copperSubset(copperSlots, context, LivingCopperFunction::isBulb);

        buildSlotMask(tick.getSnapshot(), size);

        reset();
        // 外部输入已在方法开头（稳态判定前）采样，faceInput 此时已就绪，无需再调。

        RedstonePropagation prop = new RedstonePropagation(
            edgeGrid, slotMask, faceInput, currentSnapshot, context, size, width);

        prop.phase0CountdownDelays(repeaterSlots, buttonSlots);
        Queue<Integer> queue = prop.phase1CollectSources(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, grateSlots);
        prop.phase2Propagation(queue);
        prop.phase4PowerConductors(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, copperSlots);
        prop.phase3RecheckInputs(repeaterSlots, comparatorSlots, grateSlots, bulbSlots);
        prop.phase5UpdateDisplay(torchSlots, dustSlots, lampSlots, copperSlots);
        computeFaceOutput(width, height);
        notifyBoundaryChange(context, width, height);
        steady = steady.withRecord(ContainerLivingItemHandler.getContainerRevision(context), externalSig, computeActiveTimers(repeaterSlots, buttonSlots, context));
        steadySkipCount = steady.skipCount();
    }

    /** 本 tick 是否有在途倒计时定时器（中继器 delayTimer / 按钮 pulseTimer），需要强制重算以推进时序 */
    private boolean computeActiveTimers(Set<Integer> repeaterSlots, Set<Integer> buttonSlots, ContainerContext context) {
        for (int slot : repeaterSlots) {
            ItemStack s = context.getItem(slot);
            if (s.isEmpty()) continue;
            if (LivingItemManager.getRepeaterData(s).delayTimer() > 0) return true;
        }
        for (int slot : buttonSlots) {
            ItemStack s = context.getItem(slot);
            if (s.isEmpty()) continue;
            if (LivingItemManager.getButtonData(s).pulseTimer() > 0) return true;
        }
        return false;
    }

    private void injectExternalInputs(ContainerContext context) {
        // 每次采样前清零：faceInput 为「本 tick 外部输入」的临时累积，必须在填充前清空，
        // 否则会带着上一 tick 的残留值（calculate 开头已提前调用一次用于稳态判定）。
        java.util.Arrays.fill(faceInput, 0);

        Level level = context.getLevel();
        if (level == null) return;

        List<BlockPos> positions = context.getAssociatedBlockPositions();
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (state == null) continue;

            Direction facing = CrossContainerTransfer.getBlockFacing(state);
            if (facing == null) continue;

            for (Direction worldDir : Direction.Plane.HORIZONTAL) {
                BlockPos neighborPos = pos.relative(worldDir);
                int signal = level.getSignal(neighborPos, worldDir);

                ContainerRedstoneData neighborData = ContainerLivingItemHandler.getRedstoneDataByPos(level, neighborPos);
                if (neighborData != null) {
                    BlockState neighborState = level.getBlockState(neighborPos);
                    Direction neighborFacing = CrossContainerTransfer.getBlockFacing(neighborState);
                    if (neighborFacing != null) {
                        Pos2D neighborGridDir = CrossContainerTransfer.worldToGrid(worldDir.getOpposite(), neighborFacing);
                        if (neighborGridDir != null && !neighborGridDir.isNone()) {
                            int neighborInternalDir = edgeIndex(neighborGridDir);
                            signal = Math.max(signal, neighborData.getBoundarySignal(neighborInternalDir));
                        }
                    }
                }

                if (signal > 0) {
                    Pos2D gridDir = CrossContainerTransfer.worldToGrid(worldDir, facing);
                    if (gridDir != null && !gridDir.isNone()) {
                        int internalDir = edgeIndex(gridDir);
                        faceInput[internalDir] = Math.max(faceInput[internalDir], signal);
                    }
                }
            }
        }
    }

    private void notifyBoundaryChange(ContainerContext context, int width, int height) {
        Level level = context.getLevel();
        if (level == null || level.isClientSide) return;

        boolean changed = !Arrays.equals(faceOutput, prevFaceOutput);
        System.arraycopy(faceOutput, 0, prevFaceOutput, 0, 4);
        if (changed) {
            for (BlockPos pos : context.getAssociatedBlockPositions()) {
                BlockState state = level.getBlockState(pos);
                if (state != null) {
                    level.updateNeighborsAt(pos, state.getBlock());
                }
            }
        }
    }

    private void computeFaceOutput(int width, int height) {
        java.util.Arrays.fill(faceOutput, 0);
        if (edgeGrid == null) return;

        // 每槽自有出边模型下，朝外的面信号 = 边界槽位朝该方向发出的出边最大值。
        for (int c = 0; c < width; c++) {
            faceOutput[E_UP] = Math.max(faceOutput[E_UP], edgeGrid.get(c, E_UP));
        }
        for (int c = 0; c < width; c++) {
            faceOutput[E_DOWN] = Math.max(faceOutput[E_DOWN], edgeGrid.get((height - 1) * width + c, E_DOWN));
        }
        for (int r = 0; r < height; r++) {
            faceOutput[E_LEFT] = Math.max(faceOutput[E_LEFT], edgeGrid.get(r * width, E_LEFT));
        }
        for (int r = 0; r < height; r++) {
            faceOutput[E_RIGHT] = Math.max(faceOutput[E_RIGHT], edgeGrid.get(r * width + (width - 1), E_RIGHT));
        }
    }

    public int getBoundarySignal(int internalDir) {
        return faceOutput[internalDir];
    }

    static boolean isConductiveBlock(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            if (!LivingItemManager.isLivingItem(stack)) return false;
            // 涂蜡 = 绝缘（§3.2）：不参与信号层的充能/发射，电力层走感应耦合
            if (com.qiqi.li.living.domain.power.LivingWaxedCopperFunction.isWaxedCopperBlock(stack.getItem())) {
                return false;
            }
            return blockItem.getBlock().defaultBlockState()
                .isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        }
        return false;
    }

    /**
     * 本槽从 dir 方向收到的「输入」= 该方向邻居朝本槽的出边。
     *
     * <p>重构抽象层（共享边 → 每槽出边）：当前仍走共享边，故
     * {@code edgeGrid.get(neighbor, oppositeDir(dir))} 等价于旧 {@code edgeGrid.get(slot, dir)}；
     * 翻存储（Step B）后此实现即成为「读邻居出边」，调用点无需再改。越界（容器边界外）
     * 取 {@code faceInput[dir]}。</p>
     */
    private int inputAt(int slot, int dir, int size, int width) {
        int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
        if (neighbor < 0) return faceInput[dir];
        return edgeGrid.get(neighbor, oppositeDir(dir));
    }

    private int maxInputOfSlot(int slot, int size, int width) {
        int max = 0;
        for (int dir = 0; dir < 4; dir++) max = Math.max(max, inputAt(slot, dir, size, width));
        return max;
    }

    private static int oppositeDir(int dir) {
        return switch (dir) {
            case E_UP -> E_DOWN;
            case E_DOWN -> E_UP;
            case E_LEFT -> E_RIGHT;
            case E_RIGHT -> E_LEFT;
            default -> dir;
        };
    }

    private static int edgeIndex(Pos2D dir) {
        if (dir.x() == 0) {
            return dir.y() < 0 ? E_UP : E_DOWN;
        } else {
            return dir.x() < 0 ? E_LEFT : E_RIGHT;
        }
    }

    /**
     * 每槽 4 条出边模型：edges[slot * 4 + dir] 表示该槽位朝 dir 方向【发出】的边信号。
     * 与旧共享边模型（相邻两槽共用同一条 hEdges/vEdges）不同，现在的边归【发出方】所有：
     *  - 写：源槽用 set(slot, dir, v) 写自己的出边；
     *  - 读：某槽读 dir 方向输入时，读的是邻居的对应出边 inputAt(slot,dir)=edgeGrid.get(neighbor,oppositeDir(dir))。
     * 边界外（neighbor<0）由 inputAt 转 faceInput[dir] 兜底。
     * reset() 每 tick 交换 edgeGrid/prevEdgeGrid 并清零，prevEdgeGrid 同名存储上一 tick 的发出边。
     */
    static class EdgeGrid {
        final int width;
        final int height;
        final int[] edges;

        EdgeGrid(int width, int height) {
            this.width = width;
            this.height = height;
            this.edges = new int[width * height * 4];
        }

        int get(int slot, int dir) {
            if (slot < 0 || slot >= width * height) return 0;
            if (dir < 0 || dir >= 4) return 0;
            return edges[slot * 4 + dir];
        }

        void set(int slot, int dir, int value) {
            if (slot < 0 || slot >= width * height) return;
            if (dir < 0 || dir >= 4) return;
            edges[slot * 4 + dir] = value;
        }

        int maxOfSlot(int slot) {
            if (slot < 0 || slot >= width * height) return 0;
            int base = slot * 4;
            int max = 0;
            for (int dir = 0; dir < 4; dir++) {
                max = Math.max(max, edges[base + dir]);
            }
            return max;
        }

        boolean anyOfSlot(int slot) {
            if (slot < 0 || slot >= width * height) return false;
            int base = slot * 4;
            for (int dir = 0; dir < 4; dir++) {
                if (edges[base + dir] > 0) return true;
            }
            return false;
        }

        void zero() {
            Arrays.fill(edges, 0);
        }
    }
}