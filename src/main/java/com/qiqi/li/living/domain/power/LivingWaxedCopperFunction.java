package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DomainSnapshot;

/**
 * 活涂蜡铜块 —— 电力层发电机 / 电池（§3.4、§3.5、§3.6）。
 *
 * <p>v3（铜块网络传播）：振荡器检测上升沿 → 通过同氧化等级的相邻铜块网络传播
 * {@link PhaseEvent} → 发电机接收事件 → 域内计 n 算合因子。
 * 不同锈蚀等级（新鲜/暴露/锈蚀/氧化）形成彼此隔离的铜块网络，
 * 玩家需要搭建铜块路径从振荡器连接到发电机才能实现 >4 相输入。
 * 涂蜡 = 绝缘 = 不参与信号层。</p>
 *
 * <p>调度：{@code getPriority()} = 3，必须晚于红石层（priority 2）——
 * 电力采样依赖红石已算完的 edgeGrid / prevEdgeGrid 双缓冲。</p>
 */
public class LivingWaxedCopperFunction implements LivingItemFunction, HasContainerData, HasDirection {

    public static final String ID = "living_waxed_copper";

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return isWaxedCopperBlock(stack.getItem());
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerPowerData powerData = tick.getOrCreatePowerData(ctx);
        if (powerData == null) return;

        // 各锈蚟级本 tick 基础出力累加器（共振用，§3.7）。
        // 声部单位是锈蚟级：同锈蚟级的多个连通块在此自然相加。
        final long[] baseReByOx = new long[PowerMath.OXIDATION_LEVELS];

        ContainerRedstoneData redstone = tick.getOrCreateRedstoneData(ctx);
        if (redstone == null) {
            // 仍需推进共振 EMA，否则停止发电的锈蚟级不会衰减
            powerData.updateOxidationEma(baseReByOx);
            powerData.endTick(0);
            return;
        }

        int size = ctx.getSize();
        int containerWidth = ctx.getWidth();
        if (containerWidth <= 0) containerWidth = 9; // fallback
        long now = powerData.currentTick();

        // ── 收集发电机（铜灯跳过）──
        Map<Integer, GeneratorState> active = new HashMap<>();
        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || isWaxedBulb(stack.getItem())) continue;
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= size) continue;
            GeneratorState gen = powerData.getOrCreateGenerator(slot);
            gen.setPreferredPeriodFromStack(stack.getCount());
            active.put(slot, gen);
        }
        if (active.isEmpty()) {
            // 仍需推进共振 EMA，否则拆除的锈蚟级会一直被算作活跃声部
            powerData.updateOxidationEma(baseReByOx);
            powerData.endTick(0);
            return;
        }

        // ── 铜块网络传播：每台发电机 BFS 遍历同氧化等级铜块网络 ──
        // 边信号跟踪器持久化在 ContainerPowerData 中（跨 tick 跟踪周期）
        // 形态决定 BFS 拓扑（§3.4 感应拓扑）：
        //   铜块/格栅：全向 4 方向，单通道
        //   雕文：仅输入方向检测 + 仅输出方向传播，单通道
        //   切制：水平/垂直各一次 BFS，独立双通道，能量相加

        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            GeneratorState gen = e.getValue();
            int pref = gen.preferredPeriod();

            ItemStack genStack = ctx.getItem(genSlot);
            int genOxidation = getOxidationLevel(genStack.getItem());
            int coilForm = getCoilForm(genStack.getItem());

            switch (coilForm) {
                case LivingWaxedGeneratorData.FORM_CHISELED -> {
                    // 雕文：定向 BFS，单通道（主通道 channel(0)）
                    var chiseledData = LivingItemManager.getWaxedChiseledData(genStack);
                    Pos2D inputDir = chiseledData.inputDir();
                    Pos2D outputDir = chiseledData.outputDir();
                    ChannelState ch = gen.channel(0);
                    runBfs(genSlot, genOxidation, ch, pref, powerData, redstone,
                        ctx, size, containerWidth, now, pos2dToEdgeDir(inputDir), outputDir, -1);
                    ch.tickCleanup(now, pref);
                    accountEnergy(gen, ch, pref, powerData, baseReByOx, genOxidation);
                }
                case LivingWaxedGeneratorData.FORM_CUT -> {
                    // 切制：双轴独立 BFS，水平→channel(0)，垂直→channel(1)
                    ChannelState chH = gen.channel(0);
                    runBfs(genSlot, genOxidation, chH, pref, powerData, redstone,
                        ctx, size, containerWidth, now, -1, null, 0); // 仅水平
                    chH.tickCleanup(now, pref);
                    accountEnergy(gen, chH, pref, powerData, baseReByOx, genOxidation);

                    ChannelState chV = gen.channel(1);
                    runBfs(genSlot, genOxidation, chV, pref, powerData, redstone,
                        ctx, size, containerWidth, now, -1, null, 1); // 仅垂直
                    chV.tickCleanup(now, pref);
                    accountEnergy(gen, chV, pref, powerData, baseReByOx, genOxidation);
                }
                default -> {
                    // 铜块 / 格栅：全向 BFS，单通道（主通道 channel(0)）
                    ChannelState ch = gen.channel(0);
                    runBfs(genSlot, genOxidation, ch, pref, powerData, redstone,
                        ctx, size, containerWidth, now, -1, null, -1);
                    ch.tickCleanup(now, pref);
                    accountEnergy(gen, ch, pref, powerData, baseReByOx, genOxidation);
                }
            }
        }

        // ── 检测仪表盘写回 ──
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            GeneratorState gen = e.getValue();
            ItemStack stack = ctx.getItem(slot);
            if (stack.isEmpty()) continue;
            int cf = getCoilForm(stack.getItem());
            var telemetry = buildTelemetry(gen, stack.getCount(), cf, powerData);
            var current = LivingItemManager.getGeneratorData(stack);
            if (!current.equals(telemetry)) {
                LivingItemManager.setGeneratorData(stack, telemetry);
                ctx.syncSlotToClients(slot, stack);
            }
        }

        // ── 每台发电机推进 EMA（per-generator EMA）──
        long totalGeneratedRe = 0;
        for (var e : active.entrySet()) {
            totalGeneratedRe += e.getValue().drainAndEndTick();
        }

        // ── 网络级共振：不同锈蚟级之间的「和声」（见 living-power-tech.md §3.7）──
        // 单遍前馈：只用本 tick 的基础出力 baseReByOx 推进 EMA，再取增益套用一次。
        // 增益绝不回灌 baseReByOx，因此「A 增益↑ → A 出力↑ → A 增益再↑」的
        // 回代环在结构上不存在（这是本机制唯一的安全红线）。
        powerData.updateOxidationEma(baseReByOx);
        double resonanceGain = powerData.resonanceGain();

        // ── 发电直存（§3.6 v17.5）──
        long generatedRe = powerData.drainGeneratedRe();
        if (resonanceGain > 1.0 && generatedRe > 0) {
            generatedRe = Math.round(generatedRe * resonanceGain);
        }
        boolean bankChanged = false;
        if (generatedRe > 0) {
            bankChanged = distributeToBulbs(generatedRe, entries);
        }
        if (bankChanged && ctx instanceof com.qiqi.li.living.container.SimpleContainerContext simpleCtx) {
            for (net.minecraft.world.level.block.entity.BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                be.setChanged();
            }
        }

        powerData.endTick(generatedRe);
    }

    // ── 铜块网络传播：BFS 辅助方法 ──

    /**
     * 将 Pos2D 方向映射为 ContainerRedstoneData 的边方向索引。
     * UP=(0,-1) → 0, DOWN=(0,1) → 1, LEFT=(-1,0) → 2, RIGHT=(1,0) → 3
     */
    private static int pos2dToEdgeDir(Pos2D dir) {
        if (dir == Pos2D.UP) return ContainerRedstoneData.EDGE_UP;
        if (dir == Pos2D.DOWN) return ContainerRedstoneData.EDGE_DOWN;
        if (dir == Pos2D.LEFT) return ContainerRedstoneData.EDGE_LEFT;
        if (dir == Pos2D.RIGHT) return ContainerRedstoneData.EDGE_RIGHT;
        // fallback: 选 UP 方向
        return ContainerRedstoneData.EDGE_UP;
    }

    /**
     * 单次 BFS：从发电机槽位出发，遍历同氧化等级铜块网络，
     * 检测边信号上升沿并注入到 {@code channel}。
     *
     * @param genSlot        发电机所在槽位
     * @param genOxidation   发电机氧化等级（限制网络连通性）
     * @param channel        目标通道
     * @param pref           偏好周期
     * @param powerData      容器电力数据（持久化 tracker）
     * @param redstone       红石数据（边信号双缓冲）
     * @param ctx            容器上下文
     * @param size           容器总槽位数
     * @param containerWidth 容器宽度（列数）
     * @param now            当前 tick
     * @param edgeDirFilter  仅检测该方向的边（-1=全部方向）；雕文用
     * @param expandDirFilter 仅扩展到该 Pos2D 方向（null=全部方向）；雕文用
     * @param axisFilter     限制扩展轴（-1=全部，0=仅水平，1=仅垂直）；切制用
     */
    private static void runBfs(
            int genSlot, int genOxidation, ChannelState channel, int pref,
            ContainerPowerData powerData, ContainerRedstoneData redstone,
            ContainerContext ctx, int size, int containerWidth, long now,
            int edgeDirFilter, Pos2D expandDirFilter, int axisFilter) {

        Set<Integer> visited = new HashSet<>();
        Queue<Integer> queue = new LinkedList<>();
        queue.add(genSlot);
        visited.add(genSlot);

        while (!queue.isEmpty()) {
            int current = queue.poll();
            int row = current / containerWidth;
            int col = current % containerWidth;

            // 检查该槽位的边信号（可受方向过滤）
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                if (edgeDirFilter >= 0 && dir != edgeDirFilter) continue;
                int signal = redstone.getEdgeValue(current, dir);
                int prevSignal = redstone.getPrevEdgeValue(current, dir);
                if (signal == prevSignal) continue;
                int delta = signal - prevSignal;
                int absDelta = Math.abs(delta);
                if (delta > 0) {
                    long edgeKey = ((long) current << 2) | dir;
                    SignalTracker tracker = powerData.getOrCreateEdgeTracker(edgeKey);
                    tracker.onRisingEdge(now, absDelta);
                    if (tracker.period() > 0) {
                        channel.onPhaseEvent(new PhaseEvent(
                            (int) edgeKey, tracker.period(),
                            tracker.offset(), absDelta, now), pref);
                    }
                }
            }

            // 遍历四个方向找同氧化等级的铜块邻居
            // dirs: {row, col} → up, down, left, right
            int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] d : dirs) {
                // 轴过滤：切制水平/垂直隔离
                // axisFilter=0（水平轴）：只允许列变化（左右），d[1] != 0
                // axisFilter=1（垂直轴）：只允许行变化（上下），d[0] != 0
                if (axisFilter == 0 && d[1] == 0) continue;
                if (axisFilter == 1 && d[0] == 0) continue;
                // 方向过滤：雕文只向输出方向扩展
                if (expandDirFilter != null) {
                    int dr = d[0] - expandDirFilter.y(); // row 方向
                    int dc = d[1] - expandDirFilter.x(); // col 方向
                    if (dr != 0 || dc != 0) continue;
                }
                int nr = row + d[0];
                int nc = col + d[1];
                if (nr < 0 || nc < 0 || nc >= containerWidth) continue;
                int neighbor = nr * containerWidth + nc;
                if (neighbor < 0 || neighbor >= size) continue;
                if (visited.contains(neighbor)) continue;
                ItemStack neighborStack = ctx.getItem(neighbor);
                if (neighborStack.isEmpty()) continue;
                if (!isWaxedCopperBlock(neighborStack.getItem())) continue;
                if (isWaxedBulb(neighborStack.getItem())) continue; // 铜灯不导电
                if (getOxidationLevel(neighborStack.getItem()) != genOxidation) continue;
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }
    }

    /**
     * 从通道最佳域计算能量并记入发电机和容器。
     *
     * <p>同时按锈蚟级累加**基础**出力到 {@code baseReByOx}，供网络级共振使用
     * （见 living-power-tech.md §3.7）。这里累的是共振前的值——共振增益只在
     * tick 末统一套用一次，绝不回灌。</p>
     *
     * @param baseReByOx 各锈蚟级基础出力累加器（可为 null，表示不统计共振）
     * @param oxidation  该发电机所属锈蚟级（0~3）
     */
    private static void accountEnergy(GeneratorState gen, ChannelState channel, int pref,
                                      ContainerPowerData powerData,
                                      long[] baseReByOx, int oxidation) {
        double factor = channel.bestFactor(pref);
        int period = channel.bestPeriod(pref);
        if (factor > 0 && period > 0) {
            long re = PowerMath.eventEnergyRe(factor, period);
            if (re > 0) {
                gen.onEventEnergy(re);
                powerData.onEventEnergy(re);
                if (baseReByOx != null && oxidation >= 0 && oxidation < baseReByOx.length) {
                    baseReByOx[oxidation] += re;
                }
            }
        }
    }

    // ── 振荡器信号跟踪 ──

    /**
     * 单槽位振荡器信号跟踪器 —— 检测上升沿、估计周期和偏移。
     */
    public static class SignalTracker {
        private int lastValue;
        private long lastRisingTick = -1;
        private long prevRisingTick = -1;
        private double periodTicks;
        private int intervalsSeen;
        private int lastDelta;

        /** 记录一次值变化（用于检测上升沿，外部已判定 delta > 0 才调用） */
        public void onRisingEdge(long tick, int delta) {
            lastDelta = delta;
            if (lastRisingTick >= 0) {
                long interval = tick - lastRisingTick;
                if (interval > 0) {
                    periodTicks = intervalsSeen == 0
                        ? interval
                        : periodTicks + (interval - periodTicks) * 0.5;
                    intervalsSeen++;
                }
                prevRisingTick = lastRisingTick;
            }
            lastRisingTick = tick;
        }

        /** 周期估计（tick）；少于 2 个间隔则 0 */
        public int period() {
            if (intervalsSeen < 1 || periodTicks < 1.5) return 0;
            return (int) Math.round(periodTicks);
        }

        /** 在当前周期内的相位偏移（0 ~ period-1） */
        public int offset() {
            if (period() <= 0 || lastRisingTick < 0) return 0;
            return (int) (lastRisingTick % period());
        }

        /** 上次跳变幅度 */
        public int lastDelta() {
            return lastDelta;
        }
    }

    // ── 仪表盘构建 ──

    /**
     * 从发电机状态构建检测仪表盘快照（纯逻辑，可单测）。
     */
    static LivingWaxedGeneratorData buildTelemetry(
            GeneratorState gen, int stackCount, int coilForm, ContainerPowerData powerData) {
        ChannelState channel = gen.channel();
        int pref = gen.preferredPeriod();
        int bestPeriod = channel.bestPeriod(pref);
        int bestN = channel.bestN(pref);
        int bestDelta = channel.bestDelta();
        double effDeltaSum = channel.bestEffDeltaSum(pref);

        // 全部域快照（F3+H 显示用），包含双通道
        List<DomainSnapshot> domainSnapshots = new ArrayList<>();
        collectDomains(channel, domainSnapshots);
        if (coilForm == LivingWaxedGeneratorData.FORM_CUT) {
            collectDomains(gen.channel(1), domainSnapshots);
        }

        // 每个发电机独立 EMA 功率 + 容器总功率
        long emaFe = gen.getEmaPowerFe();
        long containerEmaFe = powerData != null ? powerData.getEmaPowerFe() : 0;

        // 网络级共振（容器级，§3.6.1）：只读 powerData 的 EMA 基础值，绝不回灌
        double resonanceGain = powerData != null ? powerData.resonanceGain() : 1.0;
        double resonanceBalance = powerData != null ? powerData.resonanceBalance() : 0.0;
        int activeVoices = powerData != null ? powerData.activeOxidationLevels() : 0;
        List<Long> voicePower = (powerData != null)
            ? toVoicePowerList(powerData.getEmaPowerByOxidation())
            : List.of(0L, 0L, 0L, 0L);

        if (bestN <= 0) {
            return new LivingWaxedGeneratorData(
                0, 0, 0, 0, 0, coilForm, emaFe, containerEmaFe, domainSnapshots,
                resonanceGain, resonanceBalance, activeVoices, voicePower);
        }
        double eff = PowerMath.tuningEfficiency(
            Math.abs(bestPeriod - pref), pref);
        double unlock = Math.min(1.0, eff * bestN / pref);
        int unlockPermille = (int) Math.round(unlock * 1000);
        int effDeltaSumPermille = (int) Math.round(effDeltaSum * 1000);
        return new LivingWaxedGeneratorData(
            bestPeriod, bestN, unlockPermille, bestDelta, effDeltaSumPermille,
            coilForm, emaFe, containerEmaFe, domainSnapshots,
            resonanceGain, resonanceBalance, activeVoices, voicePower);
    }

    /** double[] (各声部基础出力 EMA) → List<Long>（RE/t，诊断展示用） */
    private static List<Long> toVoicePowerList(double[] ema) {
        List<Long> out = new ArrayList<>(ema.length);
        for (double v : ema) out.add(Math.round(v));
        return out;
    }

    /** 收集通道的全部域快照到 list */
    private static void collectDomains(ChannelState ch, List<DomainSnapshot> out) {
        for (var e : ch.domains().entrySet()) {
            ChannelState.PhaseDomain d = e.getValue();
            List<Integer> deltas = new ArrayList<>(d.deltaByOffset().values());
            out.add(new DomainSnapshot(
                d.period(), d.n(), d.maxDelta(), d.effDeltaSum(), deltas));
        }
    }

    /**
     * 发电直存（§3.6 v17.5）：本 tick 发电量按「剩余容量比例」分配入各铜灯堆。
     *
     * @param generatedRe 本 tick 发电量（RE，来自 {@code drainGeneratedRe()}）
     * @return true 表示有铜灯实际充入了电量（需 setChanged 落盘）
     */
    static boolean distributeToBulbs(long generatedRe, List<SlotEntry> entries) {
        long mfe = Math.round(generatedRe * PowerMath.RE_TO_FE * 1000.0);
        if (mfe <= 0) return false;

        record BulbRef(ItemStack stack, int count, long remaining) {}
        List<BulbRef> bulbs = new ArrayList<>();
        long totalRemaining = 0;
        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || !isWaxedBulb(stack.getItem())) continue;
            long rem = LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount())
                - LivingItemManager.getWaxedBulbData(stack).totalChargeMilliFe(stack.getCount());
            if (rem <= 0) continue;
            bulbs.add(new BulbRef(stack, stack.getCount(), rem));
            totalRemaining += rem;
        }
        if (bulbs.isEmpty()) return false;

        long distributed = 0;
        for (BulbRef ref : bulbs) {
            long share = mfe * ref.remaining() / totalRemaining;
            long perLamp = share / ref.count();
            if (perLamp <= 0) continue;
            LivingWaxedBulbData data = LivingItemManager.getWaxedBulbData(ref.stack());
            long newQ = Math.min(PowerMath.BULB_UNIT_CAPACITY_MFE,
                data.chargeMilliFe() + perLamp);
            LivingItemManager.setWaxedBulbData(ref.stack(), data.withChargeMilliFe(newQ));
            distributed += (newQ - data.chargeMilliFe()) * ref.count();
        }
        return distributed > 0;
    }

    // ── Tooltip ──

    @Override
    public void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag,
                             ItemStack stack) {
        var item = stack.getItem();
        int oxidation = getOxidationLevel(item);

        // ── 标题行：形态 + 名称 ──
        tooltipAdder.accept(Component.nullToEmpty(""));
        String formKey = formTranslationKey(item);
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.waxed_copper.title",
                Component.translatable(formKey))
            .withStyle(ChatFormatting.GOLD));

        // ── 雕文输入/输出方向（仅涂蜡雕文）──
        if (isWaxedChiseled(item)) {
            var chiseledData = LivingItemManager.getWaxedChiseledData(stack);
            String inputSym = chiseledData.inputDir().getSymbol();
            String outputSym = chiseledData.outputDir().getSymbol();
            tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.waxed_copper.chiseled_dir",
                    inputSym, outputSym)
                .withStyle(ChatFormatting.GRAY));
        }

        if (!isWaxedBulb(item)) {
            var t = LivingItemManager.getGeneratorData(stack);
            int pref = stack.getCount();
            boolean hasSignal = t.detectedPeriod() > 0;

            // ── 偏好周期 / 宽带（紧跟在标题后）──
            if (pref >= 2) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.preferred_period"))
                    .append(Component.literal(": " + pref + " tick"))
                    .withStyle(ChatFormatting.AQUA));
            } else {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.broadband"))
                    .withStyle(ChatFormatting.DARK_AQUA));
            }

            // ── 网络级共振（容器级，§3.6.1）──
            renderResonanceTooltip(tooltipAdder, t, flag);

            // ── EMA 功率（FE/t）──
            if (t.emaPowerFe() > 0) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.ema_power"))
                    .append(Component.literal(": " + t.emaPowerFe() + " FE/t"))
                    .withStyle(ChatFormatting.YELLOW));
            }

            if (hasSignal) {
                // ── 检测周期 · 相数 · 解锁度（合并为一行）──
                boolean tuned = Math.abs(t.detectedPeriod() - pref) <= 1;
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.detected_period"))
                    .append(Component.literal(": " + t.detectedPeriod() + "t"))
                    .append(Component.literal(tuned ? " §a✓" : " §c(偏好" + pref + "t)"))
                    .append(Component.literal("  §7n=" + t.phaseCount()))
                    .append(Component.literal("  §7解锁" + t.unlockPermille() / 10 + "%"))
                    .withStyle(ChatFormatting.GRAY));

                // ── 发电量公式（v3，一行）──
                double effDeltaSum = t.effDeltaSumPermille() / 1000.0;
                double factor = PowerMath.combinedFactor(effDeltaSum, t.phaseCount(), t.unlockPermille() / 1000.0);
                double fePerEvent = factor * (t.detectedPeriod() / 16.0);
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.power_output"))
                    .append(Component.literal(" = " + String.format("%.0f", fePerEvent) + " FE"))
                    .append(Component.literal("  §8(Σ√|Δ|=" + String.format("%.1f", effDeltaSum)))
                    .append(Component.literal(" ^(1+" + String.format("%.2f", t.unlockPermille() / 1000.0) + ")"))
                    .append(Component.literal(" × " + t.detectedPeriod() + "/16)"))
                    .withStyle(ChatFormatting.GRAY));
            } else {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.no_signal"))
                    .withStyle(ChatFormatting.DARK_GRAY));
            }

            // ── F3+H 高级模式：容器总功率 + 各域快照 ──
            if (flag.isAdvanced()) {
                // 容器总功率
                if (t.containerEmaPowerFe() > 0) {
                    tooltipAdder.accept(Component.literal("  ")
                        .append(Component.translatable("tooltip.livingitem.waxed_copper.container_power"))
                        .append(Component.literal(": " + t.containerEmaPowerFe() + " FE/t"))
                        .withStyle(ChatFormatting.DARK_GRAY));
                }
                // 各域快照（紧凑格式）
                for (var ds : t.domains()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  §5P=").append(ds.period()).append("t")
                      .append("  n=").append(ds.n())
                      .append("  Σ√|Δ|=").append(String.format("%.1f", ds.effDeltaSum()))
                      .append("  max|Δ|=").append(ds.maxDelta());
                    tooltipAdder.accept(Component.literal(sb.toString())
                        .withStyle(ChatFormatting.DARK_PURPLE));
                    // 各偏移的 |Δ|（inline 展示）
                    if (!ds.deltas().isEmpty()) {
                        StringBuilder sb2 = new StringBuilder("    ");
                        for (int i = 0; i < ds.deltas().size(); i++) {
                            sb2.append("[").append(i).append("]=").append(ds.deltas().get(i)).append(" ");
                        }
                        tooltipAdder.accept(Component.literal(sb2.toString())
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
                    }
                }
            }
        }
        // ── 铜灯电量 ──
        if (isWaxedBulb(item)) {
            LivingWaxedBulbData data = LivingItemManager.getWaxedBulbData(stack);
            long q = data.chargeMilliFe() * stack.getCount();
            long cap = LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount());
            boolean full = q >= cap;
            String qStr = q >= 1000 ? String.valueOf(q / 1000)
                : String.format("%.2f", q / 1000.0);
            String capStr = cap >= 1000 ? String.valueOf(cap / 1000)
                : String.format("%.2f", cap / 1000.0);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.battery"))
                .append(Component.literal(": " + qStr + " / " + capStr + " FE"
                    + (full ? "（已满）" : "")))
                .withStyle(q > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 渲染网络级共振的 tooltip 行（容器级，§3.6.1）。
     *
     * <p>共振是容器级信息，因此推到每台发电机的仪表盘组件里统一显示——
     * 同一容器内的任一发电机 tooltip 都能看到当前的共振增益 / 平衡度 / 声部数。</p>
     */
    private static void renderResonanceTooltip(
            java.util.function.Consumer<Component> tooltipAdder,
            LivingWaxedGeneratorData t, TooltipFlag flag) {
        int voices = t.activeVoices();
        if (voices >= 2) {
            double gain = t.resonanceGain();
            int balPct = (int) Math.round(t.resonanceBalance() * 100);
            // 满共振（R=4 → gain=16）用金色高亮，其余用青色
            double maxGain = Math.pow(PowerMath.OXIDATION_LEVELS, PowerMath.RESONANCE_EXPONENT);
            ChatFormatting gainFmt = gain >= maxGain - 1e-6 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.resonance").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" ×" + String.format("%.0f", gain)).withStyle(gainFmt))
                .append(Component.literal("  "))
                .append(Component.translatable("tooltip.livingitem.waxed_copper.resonance_balance").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" " + balPct + "%").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  "))
                .append(Component.translatable("tooltip.livingitem.waxed_copper.resonance_voices").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" " + voices + "/" + PowerMath.OXIDATION_LEVELS).withStyle(ChatFormatting.GRAY)));
            if (flag.isAdvanced()) {
                // 各声部基础出力（RE/t，EMA）—— 诊断「哪一级是短板」
                var vp = t.voicePower();
                String[] names = {"未锈", "暴露", "风化", "氧化"};
                StringBuilder sb = new StringBuilder("  §8声部出力");
                for (int i = 0; i < vp.size() && i < names.length; i++) {
                    if (vp.get(i) > 0) sb.append(" [").append(names[i]).append("]=").append(vp.get(i));
                }
                tooltipAdder.accept(Component.literal(sb.toString()).withStyle(ChatFormatting.DARK_GRAY));
            }
        } else if (voices == 1) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.waxed_copper.resonance_none_single")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        // voices == 0：容器无任何发电，不显示共振行
    }

    /** 从物品取形态翻译键 */
    private static String formTranslationKey(Item item) {
        if (isWaxedChiseled(item)) return "tooltip.livingitem.waxed_copper.form.chiseled";
        if (isWaxedCut(item)) return "tooltip.livingitem.waxed_copper.form.cut";
        if (isWaxedGrate(item)) return "tooltip.livingitem.waxed_copper.form.grate";
        return "tooltip.livingitem.waxed_copper.form.block";
    }

    // ── WASD 方向配置（涂蜡雕文的输入/输出方向，信号层同款 2 键配置）──

    private static final String[] CHISELED_SLOT_NAMES = {"input", "output"};

    @Override
    public int getDirectionKeyCount() {
        return 2;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return CHISELED_SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (!isWaxedChiseled(stack.getItem())) return false;
        var data = LivingItemManager.getWaxedChiseledData(stack);
        switch (slotName) {
            case "input" -> {
                LivingItemManager.setWaxedChiseledData(stack, data.withInputDir(direction));
                return true;
            }
            case "output" -> {
                LivingItemManager.setWaxedChiseledData(stack, data.withOutputDir(direction));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ── 静态工具方法 ──

    /** 从物品取线圈形态（用于 telemetry 和 tooltip 显示） */
    static int getCoilForm(Item item) {
        if (isWaxedChiseled(item)) return LivingWaxedGeneratorData.FORM_CHISELED;
        if (isWaxedCut(item)) return LivingWaxedGeneratorData.FORM_CUT;
        if (isWaxedGrate(item)) return LivingWaxedGeneratorData.FORM_GRATE;
        return LivingWaxedGeneratorData.FORM_BLOCK;
    }

    /** 全部涂蜡铜块家族（发电机体 + 电池），共 20 件 */
    public static boolean isWaxedCopperBlock(Item item) {
        return item == Items.WAXED_COPPER_BLOCK || item == Items.WAXED_EXPOSED_COPPER
            || item == Items.WAXED_WEATHERED_COPPER || item == Items.WAXED_OXIDIZED_COPPER
            || item == Items.WAXED_CHISELED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CHISELED_COPPER || item == Items.WAXED_OXIDIZED_CHISELED_COPPER
            || item == Items.WAXED_CUT_COPPER || item == Items.WAXED_EXPOSED_CUT_COPPER
            || item == Items.WAXED_WEATHERED_CUT_COPPER || item == Items.WAXED_OXIDIZED_CUT_COPPER
            || item == Items.WAXED_COPPER_GRATE || item == Items.WAXED_EXPOSED_COPPER_GRATE
            || item == Items.WAXED_WEATHERED_COPPER_GRATE || item == Items.WAXED_OXIDIZED_COPPER_GRATE
            || item == Items.WAXED_COPPER_BULB || item == Items.WAXED_EXPOSED_COPPER_BULB
            || item == Items.WAXED_WEATHERED_COPPER_BULB || item == Items.WAXED_OXIDIZED_COPPER_BULB;
    }

    /** 涂蜡铜块本体（1 线圈 × 4 向全叠加） */
    public static boolean isWaxedBase(Item item) {
        return item == Items.WAXED_COPPER_BLOCK || item == Items.WAXED_EXPOSED_COPPER
            || item == Items.WAXED_WEATHERED_COPPER || item == Items.WAXED_OXIDIZED_COPPER;
    }

    /** 涂蜡雕文（1 线圈 × 1 向，输入/输出双方向 WASD 配置） */
    public static boolean isWaxedChiseled(Item item) {
        return item == Items.WAXED_CHISELED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CHISELED_COPPER || item == Items.WAXED_OXIDIZED_CHISELED_COPPER;
    }

    /** 涂蜡切制（2 线圈 H/V 隔离） */
    public static boolean isWaxedCut(Item item) {
        return item == Items.WAXED_CUT_COPPER || item == Items.WAXED_EXPOSED_CUT_COPPER
            || item == Items.WAXED_WEATHERED_CUT_COPPER || item == Items.WAXED_OXIDIZED_CUT_COPPER;
    }

    /** 涂蜡格栅（1 线圈 × 4 向 + 频率过滤，过滤待定） */
    public static boolean isWaxedGrate(Item item) {
        return item == Items.WAXED_COPPER_GRATE || item == Items.WAXED_EXPOSED_COPPER_GRATE
            || item == Items.WAXED_WEATHERED_COPPER_GRATE || item == Items.WAXED_OXIDIZED_COPPER_GRATE;
    }

    /** 涂蜡铜灯（电池，专职储能不发电） */
    public static boolean isWaxedBulb(Item item) {
        return item == Items.WAXED_COPPER_BULB || item == Items.WAXED_EXPOSED_COPPER_BULB
            || item == Items.WAXED_WEATHERED_COPPER_BULB || item == Items.WAXED_OXIDIZED_COPPER_BULB;
    }

    /** 锈蚀档位 0~3（用于铜块网络分组，同等级才互通） */
    public static int getOxidationLevel(Item item) {
        if (item == Items.WAXED_COPPER_BLOCK || item == Items.WAXED_CHISELED_COPPER
            || item == Items.WAXED_CUT_COPPER || item == Items.WAXED_COPPER_GRATE
            || item == Items.WAXED_COPPER_BULB) {
            return 0;
        }
        if (item == Items.WAXED_EXPOSED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_EXPOSED_CUT_COPPER || item == Items.WAXED_EXPOSED_COPPER_GRATE
            || item == Items.WAXED_EXPOSED_COPPER_BULB) {
            return 1;
        }
        if (item == Items.WAXED_WEATHERED_COPPER || item == Items.WAXED_WEATHERED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CUT_COPPER || item == Items.WAXED_WEATHERED_COPPER_GRATE
            || item == Items.WAXED_WEATHERED_COPPER_BULB) {
            return 2;
        }
        return 3;
    }
}