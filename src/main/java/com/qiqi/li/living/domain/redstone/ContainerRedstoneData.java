package com.qiqi.li.living.domain.redstone;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.List;
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

    /**
     * 中继器一个档位对应的 game tick 数。
     *
     * <p>原版「1 红石刻 = 2 game tick」，档位 N 的中继器延迟 2N game tick。
     * 本系统每 game tick 传播一次，因此充能时需把档位换算为 game tick 数。</p>
     */
    private static final int TICKS_PER_REPEATER_STEP = 2;

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
    private long lastTickTime;

    // ── 稳态跳过（steady-state skip）相关状态：跨 tick 持久 ──
    // 物品修订计数与外部输入签名都不变、且无在途倒计时定时器时，本 tick 传播结果
    // 与上一 tick 完全一致，可复用 edgeGrid 直接跳过整段 calculate，省去 BFS 等开销。
    // 跳过是安全的：物品变更会 bump 修订计数（rev 变），邻居/原版红石变化会改变外部输入
    // 签名；唯一不受这两者驱动的逐 tick 演化是中继器 delayTimer / 按钮 pulseTimer 倒计时，
    // 故用 lastHadActiveTimers 作保险——只要有倒计时在跑就强制重算。
    private boolean everCalculated = false;
    private long lastRev = -1;
    private int lastExternalSig = 0;
    private boolean lastHadActiveTimers = false;
    /** 稳态跳过次数（性能观测 / 测试可见） */
    public int steadySkipCount = 0;

    public ContainerRedstoneData() {
        this.processedThisTick = false;
        this.lastTickTime = System.currentTimeMillis();
    }

    public long getLastTickTime() {
        return lastTickTime;
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

    private void markSlots(Set<Integer> slots, int bit, int size) {
        for (int slot : slots) {
            if (slot >= 0 && slot < size) slotMask[slot] |= bit;
        }
    }

    /** 槽位是否属于给定类型集合（mask 为若干 BIT_ 的或） */
    private boolean is(int slot, int mask) {
        return slot >= 0 && slot < slotMask.length && (slotMask[slot] & mask) != 0;
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

    private boolean canConnect(int slot, int neighbor, int size, int width, ContainerContext context) {
        if (neighbor < 0 || neighbor >= size) return false;
        ItemStack neighborStack = context.getItem(neighbor);
        if (neighborStack.isEmpty()) return false;

        boolean slotIsCopper = is(slot, BIT_COPPER);
        boolean neighborIsCopper = is(neighbor, BIT_COPPER);
        boolean neighborIsDust = is(neighbor, BIT_DUST);

        if (!slotIsCopper && neighborIsCopper) return true;
        if (slotIsCopper && neighborIsDust) return true;

        if (slotIsCopper && neighborIsCopper) {
            return getOxidationLevel(slot) == getOxidationLevel(neighbor);
        }

        return true;
    }

    private int getOxidationLevel(int slot) {
        // 优先读实时物品：氧化等级可能因天气等就地变化，经 syncSlotToClients 已 bump 修订计数；
        // 直接读物品可绝对避免缓存滞后。快照 capOf 作为备用（亦供电力层稳态使用）。
        if (currentCtx != null) {
            ItemStack stack = currentCtx.getItem(slot);
            if (!stack.isEmpty()) return LivingCopperFunction.getOxidationLevel(stack.getItem());
        }
        if (currentSnapshot != null && slot >= 0 && slot < currentSnapshot.getCapOf().length) {
            int lvl = currentSnapshot.getCapOf(slot);
            if (lvl >= 0) return lvl;
        }
        return -1;
    }

    public void calculate(ContainerContext context, TickContext tick) {
        if (processedThisTick) return;
        processedThisTick = true;
        lastTickTime = System.currentTimeMillis();

        int size = context.getSize();
        int width = context.getWidth();
        int height = (size + width - 1) / width;

        // ── 稳态跳过判定（放最前，尽量早返回省开销）──
        // 先采样外部输入（便宜：邻居世界信号 + 相邻活容器边界信号）算签名；
        // 物品修订计数与外部签名都不变、且无在途倒计时定时器时，本 tick 传播结果与上 tick
        // 完全一致，可直接复用 edgeGrid 跳过整段 calculate（含 BFS）。
        // 安全性：物品变更会 bump 修订计数（rev 变），邻居/原版红石变化会改变外部输入签名；
        // 唯一不受这两者驱动的逐 tick 演化是中继器 delayTimer / 按钮 pulseTimer 倒计时，
        // 故用 lastHadActiveTimers 作保险——只要有倒计时在跑就强制重算，绝不冻结时序。
        long rev = ContainerLivingItemHandler.getContainerRevision(context);
        injectExternalInputs(context);
        int externalSig = java.util.Arrays.hashCode(faceInput);

        if (everCalculated && rev == lastRev && externalSig == lastExternalSig && !lastHadActiveTimers) {
            steadySkipCount++;
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
            recordSteadyState(context, externalSig, false);
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

        phase0CountdownDelays(repeaterSlots, buttonSlots, size, width, context);
        Queue<Integer> queue = phase1CollectSources(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, grateSlots,
            size, width, context);
        phase2Propagation(queue, size, width, height, context);
        phase4PowerConductors(torchSlots, buttonSlots, leverSlots,
            repeaterSlots, comparatorSlots, dustSlots, redstoneBlockSlots, copperSlots,
            size, width, height, context);
        phase3RecheckInputs(repeaterSlots, comparatorSlots, grateSlots, bulbSlots,
            size, width, context);
        phase5UpdateDisplay(torchSlots, dustSlots, lampSlots, copperSlots, size, width, context);
        computeFaceOutput(width, height);
        notifyBoundaryChange(context, width, height);
        recordSteadyState(context, externalSig, computeActiveTimers(repeaterSlots, buttonSlots, context));
    }

    /**
     * 记录稳态判定所需的上一 tick 状态，供下次 calculate 决定是否跳过。
     *
     * <p>注意：lastRev 必须取<b>本次实算结束后</b>的修订计数，而非方法开头的采样值——
     * 传播过程中 phase5/phase3 经 syncSlotToClients 会 bump 修订计数（生产契约：
     * 就地变更走 syncSlotToClients(bump)），若记开头采样值会导致 lastRev 落后一拍，
     * 下一拍开头采到的 rev 永远与之不等，稳态跳过几乎无法触发。</p>
     */
    private void recordSteadyState(ContainerContext context, int externalSig, boolean activeTimers) {
        lastRev = ContainerLivingItemHandler.getContainerRevision(context);
        lastExternalSig = externalSig;
        lastHadActiveTimers = activeTimers;
        everCalculated = true;
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

    private void phase0CountdownDelays(Set<Integer> repeaterSlots, Set<Integer> buttonSlots,
            int size, int width, ContainerContext context) {
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (data.locked()) continue;
            if (!data.powered()) continue;
            if (data.delayTimer() == 0) continue;

            if (data.delayTimer() > 0) {
                data = data.withDelayTimer(data.delayTimer() - 1);
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            } else {
                int newTimer = data.delayTimer() + 1;
                if (newTimer == 0) {
                    data = data.withPowered(false).withDelayTimer(0);
                } else {
                    data = data.withDelayTimer(newTimer);
                }
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (data.pressed() && data.pulseTimer() > 0) {
                int newTimer = data.pulseTimer() - 1;
                if (newTimer <= 0) {
                    LivingItemManager.setButtonData(stack, data.withPressed(false).withPulseTimer(0));
                    context.syncSlotToClients(slot, stack);
                } else {
                    LivingItemManager.setButtonData(stack, data.withPulseTimer(newTimer));
                    context.syncSlotToClients(slot, stack);
                }
            }
        }
    }

    private Queue<Integer> phase1CollectSources(Set<Integer> torchSlots, Set<Integer> buttonSlots,
            Set<Integer> leverSlots, Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> dustSlots, Set<Integer> redstoneBlockSlots, Set<Integer> grateSlots,
            int size, int width, ContainerContext context) {
        Queue<Integer> queue = new ArrayDeque<>();

        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            if (!data.isLit()) continue;

            int cap = getSignalCap(stack.getCount());
            int skipDir = edgeIndex(data.direction().opposite());

            for (int dir = 0; dir < 4; dir++) {
                if (dir == skipDir) continue;
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (!data.pressed()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : leverSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingLeverData data = LivingItemManager.getLeverData(stack);
            if (!data.powered()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (!data.powered() || data.delayTimer() > 0) continue;

            int cap = getSignalCap(stack.getCount());
            int outDir = edgeIndex(data.direction());
            int neighbor = ContainerContext.resolveNeighbor(slot, outDir, size, width);
            if (cap > edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, cap);
                if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                    queue.add(neighbor);
                }
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, context, size, width);
            if (output <= 0) continue;

            int outDir = edgeIndex(data.direction());
            int neighbor = ContainerContext.resolveNeighbor(slot, outDir, size, width);
            if (output > edgeGrid.get(slot, outDir)) {
                edgeGrid.set(slot, outDir, output);
                if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                    queue.add(neighbor);
                }
            }
        }

        for (int slot : redstoneBlockSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (cap > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, cap);
                    if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        for (int slot : grateSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingGrateData data = LivingItemManager.getGrateData(stack);
            if (data.sumSignal() <= 0) continue;

            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (neighbor < 0 || neighbor >= size) continue;
                if (!is(neighbor, BIT_COPPER)) continue;
                if (getOxidationLevel(slot) != getOxidationLevel(neighbor)) continue;

                if (data.sumSignal() > edgeGrid.get(slot, dir)) {
                    edgeGrid.set(slot, dir, data.sumSignal());
                    queue.add(neighbor);
                }
            }
        }

        return queue;
    }

    private void phase2Propagation(Queue<Integer> queue,
            int size, int width, int height, ContainerContext context) {
        while (!queue.isEmpty()) {
            int current = queue.poll();

            if (is(current, BIT_DUST)) {
                ItemStack stack = context.getItem(current);
                if (stack.isEmpty()) continue;

                int maxInput = maxInputOfSlot(current, size, width);
                if (maxInput <= 1) continue;

                int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));

                for (int dir = 0; dir < 4; dir++) {
                    int neighbor = ContainerContext.resolveNeighbor(current, dir, size, width);
                    int currentEdge = edgeGrid.get(current, dir);
                    if (output <= currentEdge) continue;
                    edgeGrid.set(current, dir, output);

                    if (is(neighbor, BIT_DUST | BIT_COPPER)) {
                        queue.add(neighbor);
                    }
                }
                continue;
            }

            if (is(current, BIT_COPPER)) {
                if (is(current, BIT_GRATE)) continue;

                ItemStack stack = context.getItem(current);
                if (stack.isEmpty()) continue;

                int maxInput = maxInputOfSlot(current, size, width);
                int cap = getSignalCap(stack.getCount());
                int output = Math.min(maxInput, cap);

                if (is(current, BIT_CHISELED)) {
                    ItemStack chiseledStack = context.getItem(current);
                    LivingCutCopperData data = LivingItemManager.getCutCopperData(chiseledStack);
                    int inputEdge = edgeIndex(data.inputDir());
                    int outputEdge = edgeIndex(data.outputDir());
                    int chiseledInput = inputAt(current, inputEdge, size, width);
                    int chiseledCap = getSignalCap(chiseledStack.getCount());
                    int chiseledOutput = Math.min(chiseledInput, chiseledCap);
                    propagateDir(current, outputEdge, chiseledOutput, size, width, context, queue);
                    continue;
                }

                if (is(current, BIT_CUT)) {
                    int maxHInput = Math.max(
                        inputAt(current, E_LEFT, size, width),
                        inputAt(current, E_RIGHT, size, width));
                    int maxVInput = Math.max(
                        inputAt(current, E_UP, size, width),
                        inputAt(current, E_DOWN, size, width));
                    int outputH = Math.min(maxHInput, cap);
                    int outputV = Math.min(maxVInput, cap);

                    propagateDir(current, E_LEFT, outputH, size, width, context, queue);
                    propagateDir(current, E_RIGHT, outputH, size, width, context, queue);
                    propagateDir(current, E_UP, outputV, size, width, context, queue);
                    propagateDir(current, E_DOWN, outputV, size, width, context, queue);
                    continue;
                }

                for (int dir = 0; dir < 4; dir++) {
                    propagateDir(current, dir, output, size, width, context, queue);
                }
            }
        }
    }

    private void propagateDir(int slot, int dir, int signal, int size, int width,
            ContainerContext context, Queue<Integer> queue) {
        int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
        if (neighbor < 0) return;
        if (is(neighbor, BIT_GRATE)) return;
        if (!canConnect(slot, neighbor, size, width, context)) return;

        int currentEdge = edgeGrid.get(slot, dir);
        if (signal <= currentEdge) return;
        edgeGrid.set(slot, dir, signal);

        if (is(neighbor, BIT_DUST | BIT_COPPER)) {
            queue.add(neighbor);
        }
    }

    private void phase3RecheckInputs(Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> grateSlots, Set<Integer> bulbSlots,
            int size, int width, ContainerContext context) {
        int height = (size + width - 1) / width;
        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);

            boolean locked = checkRepeaterLocked(slot, data, size, width, context);
            if (data.locked() != locked) {
                data = data.withLocked(locked);
                LivingItemManager.setRepeaterData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
            if (locked) continue;

            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = getEffectiveInput(slot, inputDir, size, width) > 0;

            if (data.powered() && data.delayTimer() > 0) continue;

            if (hasInput && !data.powered()) {
                LivingItemManager.setRepeaterData(stack,
                    data.withPowered(true).withDelayTimer(data.delay() * TICKS_PER_REPEATER_STEP));
                context.syncSlotToClients(slot, stack);
            } else if (hasInput && data.powered() && data.delayTimer() < 0) {
                LivingItemManager.setRepeaterData(stack, data.withDelayTimer(0));
                context.syncSlotToClients(slot, stack);
            } else if (!hasInput && data.powered() && data.delayTimer() == 0) {
                LivingItemManager.setRepeaterData(stack,
                    data.withDelayTimer(-data.delay() * TICKS_PER_REPEATER_STEP));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, context, size, width);
            boolean newPowered = output > 0;
            if (data.powered() != newPowered) {
                LivingItemManager.setComparatorData(stack, data.withPowered(newPowered));
                context.syncSlotToClients(slot, stack);
            }
            // 比较器出边的写值已统一由 phase4（powerConductiveNeighbor 前直接 set）负责，
            // 此处不再用 != 兜底——旧共享边模型下该兜底会误压下游粉的 -1 衰减信号。
        }

        for (int slot : grateSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int sum = 0;
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
                if (neighbor >= 0 && neighbor < size && is(neighbor, BIT_COPPER)) continue;
                sum += inputAt(slot, dir, size, width);
            }
            int cap = getSignalCap(stack.getCount());
            int result = Math.min(sum, cap);

            LivingGrateData data = LivingItemManager.getGrateData(stack);
            if (data.sumSignal() != result) {
                LivingItemManager.setGrateData(stack, data.withSumSignal(result));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : bulbSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingCopperBulbData data = LivingItemManager.getCopperBulbData(stack);
            boolean hasInput = anyInputOfSlot(slot, size, width);
            boolean changed = false;

            if (hasInput && !data.prevInput()) {
                if (data.recordedSignal() == 0) {
                    int maxInput = maxInputOfSlot(slot, size, width);
                    int cap = getSignalCap(stack.getCount());
                    int recorded = Math.min(maxInput, cap);
                    data = data.withRecordedSignal(recorded);
                } else {
                    data = data.withRecordedSignal(0);
                }
                changed = true;
            }
            if (hasInput != data.prevInput()) {
                data = data.withPrevInput(hasInput);
                changed = true;
            }

            if (changed) {
                LivingItemManager.setCopperBulbData(stack, data);
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    private void phase4PowerConductors(Set<Integer> torchSlots, Set<Integer> buttonSlots,
            Set<Integer> leverSlots, Set<Integer> repeaterSlots, Set<Integer> comparatorSlots,
            Set<Integer> dustSlots, Set<Integer> redstoneBlockSlots, Set<Integer> copperSlots,
            int size, int width, int height, ContainerContext context) {
        Queue<Integer> secondQueue = new ArrayDeque<>();

        for (int slot : dustSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            byte conn = data.connections();
            int maxInput = maxInputOfSlot(slot, size, width);
            if (maxInput <= 1) continue;

            int output = Math.min(maxInput - 1, getSignalCap(stack.getCount()));
            for (int dir = 0; dir < 4; dir++) {
                if ((conn & (1 << dir)) == 0) continue;
                powerConductiveNeighbor(slot, output, dir, size, width, context, secondQueue);
            }
        }

        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            if (!data.isLit()) continue;

            int cap = getSignalCap(stack.getCount());
            int skipDir = edgeIndex(data.direction().opposite());
            for (int dir = 0; dir < 4; dir++) {
                if (dir == skipDir) continue;
                powerConductiveNeighbor(slot, cap, dir, size, width, context, secondQueue);
            }
        }

        for (int slot : buttonSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingButtonData data = LivingItemManager.getButtonData(stack);
            if (!data.pressed()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                powerConductiveNeighbor(slot, cap, dir, size, width, context, secondQueue);
            }
        }

        for (int slot : leverSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingLeverData data = LivingItemManager.getLeverData(stack);
            if (!data.powered()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                powerConductiveNeighbor(slot, cap, dir, size, width, context, secondQueue);
            }
        }

        for (int slot : redstoneBlockSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int cap = getSignalCap(stack.getCount());
            for (int dir = 0; dir < 4; dir++) {
                powerConductiveNeighbor(slot, cap, dir, size, width, context, secondQueue);
            }
        }

        for (int slot : repeaterSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingRepeaterData data = LivingItemManager.getRepeaterData(stack);
            if (!data.powered() || data.delayTimer() > 0) continue;

            int cap = getSignalCap(stack.getCount());
            int outDir = edgeIndex(data.direction());
            powerConductiveNeighbor(slot, cap, outDir, size, width, context, secondQueue);
        }

        for (int slot : comparatorSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;
            LivingComparatorData data = LivingItemManager.getComparatorData(stack);
            int output = computeComparatorOutput(slot, data, context, size, width);
            int outDir = edgeIndex(data.direction());
            // 直接同步本槽出边（可抬可压），替代原 phase3 的 != 兜底：
            // 比较器关闭时出边归零，下游粉下一 tick 经 maxInput 自然衰减，不再残留高电平。
            edgeGrid.set(slot, outDir, output);
            if (output <= 0) continue;
            powerConductiveNeighbor(slot, output, outDir, size, width, context, secondQueue);
        }

        for (int slot : copperSlots) {
            if (slot < 0 || slot >= size) continue;
            if (is(slot, BIT_GRATE)) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int maxInput = maxInputOfSlot(slot, size, width);
            if (maxInput <= 0) continue;

            int cap = getSignalCap(stack.getCount());
            int output = Math.min(maxInput, cap);

            if (is(slot, BIT_CHISELED)) {
                LivingCutCopperData data = LivingItemManager.getCutCopperData(stack);
                int inputEdge = edgeIndex(data.inputDir());
                int outputEdge = edgeIndex(data.outputDir());
                int chiseledInput = inputAt(slot, inputEdge, size, width);
                int chiseledCap = getSignalCap(stack.getCount());
                int chiseledOutput = Math.min(chiseledInput, chiseledCap);
                powerConductiveNeighbor(slot, chiseledOutput, outputEdge, size, width, context, secondQueue);
            } else if (is(slot, BIT_CUT)) {
                int maxHInput = Math.max(
                    inputAt(slot, E_LEFT, size, width),
                    inputAt(slot, E_RIGHT, size, width));
                int maxVInput = Math.max(
                    inputAt(slot, E_UP, size, width),
                    inputAt(slot, E_DOWN, size, width));
                powerConductiveNeighbor(slot, Math.min(maxHInput, cap), E_LEFT, size, width, context, secondQueue);
                powerConductiveNeighbor(slot, Math.min(maxHInput, cap), E_RIGHT, size, width, context, secondQueue);
                powerConductiveNeighbor(slot, Math.min(maxVInput, cap), E_UP, size, width, context, secondQueue);
                powerConductiveNeighbor(slot, Math.min(maxVInput, cap), E_DOWN, size, width, context, secondQueue);
            } else {
                for (int dir = 0; dir < 4; dir++) {
                    powerConductiveNeighbor(slot, output, dir, size, width, context, secondQueue);
                }
            }
        }

        if (!secondQueue.isEmpty()) {
            phase2Propagation(secondQueue, size, width, height, context);
        }
    }

    private void powerConductiveNeighbor(int slot, int signal, int dir,
            int size, int width, ContainerContext context, Queue<Integer> secondQueue) {
        int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
        if (neighbor < 0) return;
        // 不向其它红石「元件」直接写输出边——元件自行从其输入算输出；
        // 导体（dust / copper）与红石导体方块则应被充能并继续传播。
        // 注意：原早退掩码 MASK_REDSTONE 含 BIT_DUST / BIT_COPPER，会把比较器/中继器
        // 经粉链输出的信号掐在网络外，导致「输出只能传一格」。改为只挡元件位。
        if (is(neighbor, BIT_TORCH | BIT_BUTTON | BIT_LEVER
                | BIT_REPEATER | BIT_COMPARATOR | BIT_BLOCK)) return;
        ItemStack nStack = context.getItem(neighbor);
        if (nStack.isEmpty()) return;
        boolean conductor = is(neighbor, BIT_DUST | BIT_COPPER) || isConductiveBlock(nStack);
        if (!conductor) return;

        for (int d2 = 0; d2 < 4; d2++) {
            if (signal > edgeGrid.get(neighbor, d2)) {
                edgeGrid.set(neighbor, d2, signal);
                int n2 = ContainerContext.resolveNeighbor(neighbor, d2, size, width);
                if (n2 >= 0 && is(n2, BIT_DUST | BIT_COPPER)) {
                    secondQueue.add(n2);
                }
            }
        }
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

    private boolean checkRepeaterLocked(int slot, LivingRepeaterData data,
            int size, int width, ContainerContext context) {
        Pos2D dir = data.direction();
        boolean isVertical = dir.equals(Pos2D.UP) || dir.equals(Pos2D.DOWN);
        int[] perpDirs = isVertical ? new int[]{E_LEFT, E_RIGHT} : new int[]{E_UP, E_DOWN};

        for (int perpDir : perpDirs) {
            int neighbor = ContainerContext.resolveNeighbor(slot, perpDir, size, width);
            if (is(neighbor, BIT_REPEATER)) {
                ItemStack neighborStack = context.getItem(neighbor);
                if (!neighborStack.isEmpty()) {
                    LivingRepeaterData neighborData = LivingItemManager.getRepeaterData(neighborStack);
                    if (neighborData.powered() && neighborData.delayTimer() == 0
                            && neighborData.direction().equals(requiredDir(perpDir))) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static boolean isConductiveBlock(ItemStack stack) {
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

    private void phase5UpdateDisplay(Set<Integer> torchSlots, Set<Integer> dustSlots,
            Set<Integer> lampSlots, Set<Integer> copperSlots, int size, int width, ContainerContext context) {
        int height = (size + width - 1) / width;
        for (int slot : torchSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            LivingRedstoneTorchData data = LivingItemManager.getRedstoneTorchData(stack);
            int inputDir = edgeIndex(data.direction().opposite());
            boolean hasInput = getEffectiveInput(slot, inputDir, size, width) > 0;
            boolean newLit = !hasInput;
            if (data.isLit() != newLit) {
                LivingItemManager.setRedstoneTorchData(stack, data.withLit(newLit));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : dustSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int maxSignal = maxInputOfSlot(slot, size, width);
            byte conn = computeDustConnections(slot, size, width, context);
            LivingRedstoneData data = LivingItemManager.getRedstoneData(stack);
            if (data.signalStrength() != maxSignal || data.isPowered() != (maxSignal > 0)
                || data.connections() != conn) {
                LivingItemManager.setRedstoneData(stack,
                    data.withSignal(maxSignal).withPowered(maxSignal > 0).withConnections(conn));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : lampSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            boolean hasSignal = anyInputOfSlot(slot, size, width);
            LivingRedstoneLampData data = LivingItemManager.getLampData(stack);
            if (data.lit() != hasSignal) {
                LivingItemManager.setLampData(stack, data.withLit(hasSignal));
                context.syncSlotToClients(slot, stack);
            }
        }

        for (int slot : copperSlots) {
            if (slot < 0 || slot >= size) continue;
            ItemStack stack = context.getItem(slot);
            if (stack.isEmpty()) continue;

            int maxSignal = maxInputOfSlot(slot, size, width);
            LivingCopperSignalData sigData = LivingItemManager.getCopperSignal(stack);
            if (sigData.signalStrength() != maxSignal) {
                LivingItemManager.setCopperSignal(stack, sigData.withSignal(maxSignal));
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    private static final Pos2D[] DIR_POS = {Pos2D.UP, Pos2D.DOWN, Pos2D.LEFT, Pos2D.RIGHT};

    private byte computeDustConnections(int slot, int size, int width, ContainerContext context) {
        byte conn = 0;
        for (int dir = 0; dir < 4; dir++) {
            int neighbor = ContainerContext.resolveNeighbor(slot, dir, size, width);
            if (neighbor < 0) continue;

            if (is(neighbor, BIT_REPEATER | BIT_COMPARATOR)) {
                ItemStack ns = context.getItem(neighbor);
                if (!ns.isEmpty()) {
                    Pos2D facing = is(neighbor, BIT_REPEATER)
                        ? LivingItemManager.getRepeaterData(ns).direction()
                        : LivingItemManager.getComparatorData(ns).direction();
                    Pos2D d = DIR_POS[dir];
                    if (d.equals(facing) || d.equals(facing.opposite())) {
                        conn |= (1 << dir);
                    }
                }
            } else if (is(neighbor, BIT_BUTTON | BIT_LEVER | BIT_TORCH
                    | BIT_DUST | BIT_LAMP | BIT_BLOCK | BIT_COPPER)) {
                conn |= (1 << dir);
            }
        }

        if (conn != 0) {
            boolean hasUp = (conn & 1) != 0;
            boolean hasDown = (conn & 2) != 0;
            boolean hasLeft = (conn & 4) != 0;
            boolean hasRight = (conn & 8) != 0;

            boolean noVertical = !hasUp && !hasDown;
            boolean noHorizontal = !hasLeft && !hasRight;

            if (!hasLeft && noVertical) conn |= 4;
            if (!hasRight && noVertical) conn |= 8;
            if (!hasUp && noHorizontal) conn |= 1;
            if (!hasDown && noHorizontal) conn |= 2;
        }

        return conn;
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

    private boolean anyInputOfSlot(int slot, int size, int width) {
        for (int dir = 0; dir < 4; dir++) if (inputAt(slot, dir, size, width) > 0) return true;
        return false;
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

    /** 本槽某方向收到的输入（含边界外 faceInput），见 {@link #inputAt} */
    private int getEffectiveInput(int slot, int dir, int size, int width) {
        return inputAt(slot, dir, size, width);
    }

    private int computeComparatorOutput(int slot, LivingComparatorData data,
            ContainerContext context, int size, int width) {
        int height = (size + width - 1) / width;
        int inputDir = edgeIndex(data.direction().opposite());
        int signalA = getEffectiveInput(slot, inputDir, size, width);

        ItemStack comparatorStack = context.getItem(slot);
        int signalCap = getSignalCap(comparatorStack.getCount());

        if (signalA == 0) {
            int backSlot = ContainerContext.resolveNeighbor(slot, inputDir, size, width);
            if (backSlot >= 0) {
                ItemStack backStack = context.getItem(backSlot);
                signalA = LivingComparatorFunction.readComparatorOutput(backStack, signalCap);
            }
        }

        int[] sideDirs = perpendicularEdges(inputDir);
        int signalB = 0;
        for (int sideDir : sideDirs) {
            int edgeSignal = inputAt(slot, sideDir, size, width);
            if (edgeSignal > 0) {
                signalB = Math.max(signalB, edgeSignal);
            } else {
                int sideSlot = ContainerContext.resolveNeighbor(slot, sideDir, size, width);
                if (sideSlot >= 0) {
                    ItemStack sideStack = context.getItem(sideSlot);
                    int sideOutput = LivingComparatorFunction.readComparatorOutput(sideStack, signalCap);
                    signalB = Math.max(signalB, sideOutput);
                }
            }
        }

        int output;
        if (data.subtractMode()) {
            output = Math.max(0, signalA - signalB);
        } else {
            output = signalA >= signalB ? signalA : 0;
        }

        return output;
    }

    private static int[] perpendicularEdges(int dir) {
        if (dir == E_UP || dir == E_DOWN) {
            return new int[] {E_LEFT, E_RIGHT};
        } else {
            return new int[] {E_UP, E_DOWN};
        }
    }

    private static int edgeIndex(Pos2D dir) {
        if (dir.x() == 0) {
            return dir.y() < 0 ? E_UP : E_DOWN;
        } else {
            return dir.x() < 0 ? E_LEFT : E_RIGHT;
        }
    }

    /**
     * 锁定方中继器在 perpDir 方向侧面时，它必须朝向被锁定方（即 opposite of perpDir）。
     */
    private static Pos2D requiredDir(int perpDir) {
        return switch (perpDir) {
            case E_LEFT -> Pos2D.RIGHT;
            case E_RIGHT -> Pos2D.LEFT;
            case E_UP -> Pos2D.DOWN;
            case E_DOWN -> Pos2D.UP;
            default -> Pos2D.NONE;
        };
    }

    /**
     * 每槽 4 条出边模型：edges[slot * 4 + dir] 表示该槽位朝 dir 方向【发出】的边信号。
     * 与旧共享边模型（相邻两槽共用同一条 hEdges/vEdges）不同，现在的边归【发出方】所有：
     *  - 写：源槽用 set(slot, dir, v) 写自己的出边；
     *  - 读：某槽读 dir 方向输入时，读的是邻居的对应出边 inputAt(slot,dir)=edgeGrid.get(neighbor,oppositeDir(dir))。
     * 边界外（neighbor<0）由 inputAt 转 faceInput[dir] 兜底。
     * reset() 每 tick 交换 edgeGrid/prevEdgeGrid 并清零，prevEdgeGrid 同名存储上一 tick 的发出边。
     */
    private static class EdgeGrid {
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