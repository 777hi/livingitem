package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
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

    /** BFS 方向偏移：EDGE_UP/DOWN/LEFT/RIGHT 对应的 (行,列) 增量 */
    private static final int[] DIR_ROW = {-1, 1, 0, 0};
    private static final int[] DIR_COL = {0, 0, -1, 1};

    /** 遥测快照有效数字位数：EMA 类读数量化到 3 位，稳态下钉死值以降低脏写频率（量化降脏化优化） */
    private static final int TELEMETRY_SIG_FIGS = 3;

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
        // 锈级共振单位是锈蚟级：同锈蚟级的多个连通块在此自然相加。
        final long[] baseReByOx = new long[PowerMath.OXIDATION_LEVELS];

        ContainerRedstoneData redstone = tick.getOrCreateRedstoneData(ctx);
        if (redstone == null) {
            // 仍需推进共振 EMA，否则停止发电的锈蚟级不会衰减
            powerData.updateOxidationEma(baseReByOx);
            powerData.endTick();
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
            // 仍需推进共振 EMA，否则拆除的锈蚟级会一直被算作活跃锈级
            powerData.updateOxidationEma(baseReByOx);
            powerData.endTick();
            return;
        }

        // ── 铜块网络传播：按网络组件遍历，而非逐发电机 ──
        // 同氧化等级连通块内的多台发电机共享同一张边集，逐发电机各跑一次 BFS 是 G 倍冗余。
        // 改为：每台发电机按形态归入 (TopoKey, rep, channelIdx) 组件，每组件仅锚点跑一次
        // runBfs，其余发电机 copyFrom 锚点的 ChannelState（相位历史随之同步，O(域) 极廉价）。
        // accountEnergy 仍逐发电机调用，用各自 pref 读共享域 —— 逐发电机 pref 敏感保留。

        // 每 tick 每 TopoKey 建一次组件 rep 表（≤54 槽，O(N) 可忽略）
        Map<TopoKey, int[]> repCache = new HashMap<>();
        // 组件标识 → 该组件内的发电机槽位列表
        Map<ComponentId, List<Integer>> groups = new LinkedHashMap<>();

        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            ItemStack genStack = ctx.getItem(genSlot);
            for (ChannelSpec spec : topoKeysFor(genStack)) {
                int rep = repOf(spec.topo(), genSlot, repCache, ctx, size, containerWidth);
                groups.computeIfAbsent(new ComponentId(spec.topo(), rep, spec.channelIdx()),
                    k -> new ArrayList<>()).add(genSlot);
            }
        }

        // 每组件只算一次：锚点 BFS + tickCleanup(maxPref)，其余 copyFrom
        for (var en : groups.entrySet()) {
            ComponentId cid = en.getKey();
            List<Integer> members = en.getValue();
            int anchorSlot = members.get(0);
            GeneratorState anchor = active.get(anchorSlot);
            ChannelState shared = anchor.channel(cid.channelIdx());
            int maxPref = 0;
            for (int s : members) maxPref = Math.max(maxPref, active.get(s).preferredPeriod());

            runBfs(anchorSlot, cid.topo(), shared, anchor.preferredPeriod(),
                powerData, redstone, ctx, size, containerWidth, now);
            shared.tickCleanup(now, maxPref);
            for (int i = 1; i < members.size(); i++) {
                active.get(members.get(i)).channel(cid.channelIdx()).copyFrom(shared);
            }
        }

        // 逐发电机结算能量（用各自 pref 读共享/复制后的 ChannelState）
        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            GeneratorState gen = e.getValue();
            ItemStack genStack = ctx.getItem(genSlot);
            int genOxidation = getOxidationLevel(genStack.getItem());
            for (ChannelSpec spec : topoKeysFor(genStack)) {
                accountEnergy(gen, gen.channel(spec.channelIdx()), gen.preferredPeriod(),
                    baseReByOx, genOxidation);
            }
        }

        // ── 检测仪表盘写回（运行时缓存，不写入 DataComponent，不影响物品堆叠）──
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            GeneratorState gen = e.getValue();
            ItemStack stack = ctx.getItem(slot);
            if (stack.isEmpty()) continue;
            int cf = getCoilForm(stack.getItem());
            int ox = getOxidationLevel(stack.getItem());
            var telemetry = buildTelemetry(gen, stack.getCount(), cf, ox, powerData);
            ContainerRuntimeCache.update(ctx.getContainerKey(), slot, LivingItemRuntimeData.forGenerator(telemetry));
        }

        // ── 每台发电机推进 per-generator EMA ──
        for (var e : active.entrySet()) {
            e.getValue().drainAndEndTick();
        }

        // ── 网络级共振：不同锈蚟级之间的「和声」（见 living-power-tech.md §3.7）──
        // 单遍前馈：只用本 tick 的基础出力 baseReByOx 推进 EMA，再取增益套用一次。
        // 增益绝不回灌 baseReByOx，因此「A 增益↑ → A 出力↑ → A 增益再↑」的
        // 回代环在结构上不存在（这是本机制唯一的安全红线）。
        powerData.updateOxidationEma(baseReByOx);
        double resonanceGain = powerData.resonanceGain();

        // ── 发电直存（§3.6 v18 锈级专属通道）──
        // 逐锈级实发（锈级纯度归属）：voiceRe[k] = round(baseReByOx[k] × gain)，
        // k 锈级的电只入 k 锈级的铜灯（无同色灯 → 该锈级弃）。
        boolean bankChanged = false;
        for (int k = 0; k < baseReByOx.length; k++) {
            if (baseReByOx[k] <= 0) continue;
            long voiceRe = resonanceGain > 1.0
                ? Math.round(baseReByOx[k] * resonanceGain)
                : baseReByOx[k];
            if (voiceRe <= 0) continue;
            if (distributeToBulbs(voiceRe, k, entries)) {
                bankChanged = true;
            }
        }
        if (bankChanged && ctx instanceof com.qiqi.li.living.container.SimpleContainerContext simpleCtx) {
            for (net.minecraft.world.level.block.entity.BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                be.setChanged();
            }
        }

        powerData.endTick();
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
     * 单次 BFS：从发电机槽位出发，遍历同氧化等级铜块网络（拓扑由 {@code topo} 决定），
     * 检测边信号上升沿并注入到 {@code channel}。
     *
     * @param genSlot  发电机所在槽位（作为 BFS 起点；同组件任意槽位可达性相同）
     * @param topo     连通拓扑键（氧化级 + 形态 + 轴/方向过滤）
     * @param channel  目标通道
     * @param pref     偏好周期（onPhaseEvent 实际忽略 pref，域仅按 period 分桶）
     * @param powerData 容器电力数据（持久化 tracker）
     * @param redstone 红石数据（边信号双缓冲）
     * @param ctx      容器上下文
     * @param size     容器总槽位数
     * @param containerWidth 容器宽度（列数）
     * @param now      当前 tick
     */
    private static void runBfs(
            int genSlot, TopoKey topo, ChannelState channel, int pref,
            ContainerPowerData powerData, ContainerRedstoneData redstone,
            ContainerContext ctx, int size, int containerWidth, long now) {

        boolean[] visited = new boolean[size];
        int[] queue = new int[size];
        int head = 0, tail = 0;
        queue[tail++] = genSlot;
        visited[genSlot] = true;

        while (head < tail) {
            int current = queue[head++];
            int row = current / containerWidth;
            int col = current % containerWidth;

            // 检查该槽位的边信号（雕文仅检测输入方向）
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                if (topo.inEdge() >= 0 && dir != topo.inEdge()) continue;
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

            // 遍历四方向找同氧化等级的铜块邻居（拓扑由 topo 决定）
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                int neighbor = traversableNeighbor(ctx, size, containerWidth, current, dir, topo);
                if (neighbor < 0) continue;
                if (visited[neighbor]) continue;
                visited[neighbor] = true;
                queue[tail++] = neighbor;
            }
        }
    }

    /**
     * 从 current 沿 dir 是否可达同氧化级铜块邻居；可达返回邻居槽位，否则 -1。
     * 已包含轴过滤（切制 H/V）、输出方向过滤（雕文）、氧化级过滤。
     * runBfs 与组件分组共用，避免两套邻接逻辑分叉。
     */
    private static int traversableNeighbor(ContainerContext ctx, int size, int width,
            int current, int dir, TopoKey topo) {
        if (topo.axis() == 0 && (dir == ContainerRedstoneData.EDGE_UP || dir == ContainerRedstoneData.EDGE_DOWN)) return -1;
        if (topo.axis() == 1 && (dir == ContainerRedstoneData.EDGE_LEFT || dir == ContainerRedstoneData.EDGE_RIGHT)) return -1;
        if (topo.outEdge() >= 0 && dir != topo.outEdge()) return -1;
        int row = current / width;
        int col = current % width;
        int nr = row + DIR_ROW[dir];
        int nc = col + DIR_COL[dir];
        if (nr < 0 || nc < 0 || nc >= width) return -1;
        int neighbor = nr * width + nc;
        if (neighbor < 0 || neighbor >= size) return -1;
        ItemStack ns = ctx.getItem(neighbor);
        if (ns.isEmpty()) return -1;
        if (!isWaxedCopperBlock(ns.getItem())) return -1;
        if (isWaxedBulb(ns.getItem())) return -1; // 铜灯不导电
        if (getOxidationLevel(ns.getItem()) != topo.oxidation()) return -1;
        return neighbor;
    }

    /** 一台发电机的通道拓扑规格（可能 1 或 2 个，切制 H/V 各一） */
    private static List<ChannelSpec> topoKeysFor(ItemStack stack) {
        int ox = getOxidationLevel(stack.getItem());
        int cf = getCoilForm(stack.getItem());
        if (cf == LivingWaxedGeneratorData.FORM_CHISELED) {
            var cd = LivingItemManager.getWaxedChiseledData(stack);
            int inEdge = pos2dToEdgeDir(cd.inputDir());
            int outEdge = pos2dToEdgeDir(cd.outputDir());
            return List.of(new ChannelSpec(new TopoKey(ox, cf, -1, inEdge, outEdge), 0));
        } else if (cf == LivingWaxedGeneratorData.FORM_CUT) {
            return List.of(
                new ChannelSpec(new TopoKey(ox, cf, 0, -1, -1), 0),
                new ChannelSpec(new TopoKey(ox, cf, 1, -1, -1), 1));
        } else {
            return List.of(new ChannelSpec(new TopoKey(ox, cf, -1, -1, -1), 0));
        }
    }

    /**
     * 每 tick 每 TopoKey 建一次组件 rep 表；返回 slot 所属组件 rep（组件内最小槽位）。
     * rep 稳定（= 最小铜块槽位），保证跨 tick 同一网络映射到同一 ChannelState 实例。
     */
    private static int repOf(TopoKey topo, int slot, Map<TopoKey, int[]> cache,
            ContainerContext ctx, int size, int width) {
        int[] arr = cache.get(topo);
        if (arr == null) {
            arr = new int[size];
            Arrays.fill(arr, -1);
            int[] comp = new int[size];
            int[] q = new int[size];
            for (int s = 0; s < size; s++) {
                if (arr[s] != -1) continue;
                ItemStack st = ctx.getItem(s);
                if (st.isEmpty() || !isWaxedCopperBlock(st.getItem()) || isWaxedBulb(st.getItem())) continue;
                if (getOxidationLevel(st.getItem()) != topo.oxidation()) continue;
                // 收集该组件全部槽位（弱连通）
                int cn = 0, h = 0, t = 0;
                q[t++] = s; arr[s] = s; comp[cn++] = s;
                while (h < t) {
                    int cur = q[h++];
                    for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                        int nb = traversableNeighbor(ctx, size, width, cur, dir, topo);
                        if (nb < 0 || arr[nb] != -1) continue;
                        arr[nb] = s; comp[cn++] = nb; q[t++] = nb;
                    }
                }
                int rep = s;
                for (int i = 0; i < cn; i++) rep = Math.min(rep, comp[i]);
                for (int i = 0; i < cn; i++) arr[comp[i]] = rep;
            }
            cache.put(topo, arr);
        }
        return arr[slot];
    }

    /** 连通拓扑键：决定 BFS 的连通性与边检测方向。同键 = 同一铜块网络组件 */
    private record TopoKey(int oxidation, int coilForm, int axis, int inEdge, int outEdge) {}

    /** 一台发电机的一个通道规格：拓扑 + 通道索引（0=水平/主，1=垂直） */
    private record ChannelSpec(TopoKey topo, int channelIdx) {}

    /** 组件标识：(拓扑, rep, 通道索引) */
    private record ComponentId(TopoKey topo, int rep, int channelIdx) {}

    /**
     * 从通道最佳域计算能量并记入发电机（v18：容器级无总账，改走 baseReByOx 按锈级累加）。
     *
     * <p>同时按锈蚟级累加**基础**出力到 {@code baseReByOx}，供网络级共振与
     * 锈级功率读数使用（见 living-power-tech.md §3.7）。这里累的是共振前的值——
     * 共振增益只在 tick 末统一套用一次，绝不回灌。</p>
     *
     * @param baseReByOx 各锈蚟级基础出力累加器（可为 null，表示不统计共振）
     * @param oxidation  该发电机所属锈蚟级（0~3）
     */
    private static void accountEnergy(GeneratorState gen, ChannelState channel, int pref,
                                      long[] baseReByOx, int oxidation) {
        double factor = channel.bestFactor(pref);
        int period = channel.bestPeriod(pref);
        if (factor > 0 && period > 0) {
            long re = PowerMath.eventEnergyRe(factor, period);
            if (re > 0) {
                gen.onEventEnergy(re);
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
     *
     * @param oxidation 该发电机所属锈蚀级（0~3）——容器级读数口径：本锈级 EMA 功率
     */
    static LivingWaxedGeneratorData buildTelemetry(
            GeneratorState gen, int stackCount, int coilForm, int oxidation, ContainerPowerData powerData) {
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

        // 每个发电机独立 EMA 功率 + 本锈级功率（v18：容器总账 EMA 已拆除，
        // 容器级读数 = 该发电机所属锈级的 EMA，玩家看 tooltip 就知道本锈级发多少）
        long emaFe = gen.getEmaPowerFe();
        long levelEmaFe = powerData != null ? powerData.getLevelEmaPowerFe(oxidation) : 0;

        // 网络级共振（容器级，§3.6.1）：只读 powerData 的 EMA 基础值，绝不回灌
        double resonanceGain = powerData != null ? PowerMath.quantize(powerData.resonanceGain(), TELEMETRY_SIG_FIGS) : 1.0;
        double resonanceBalance = powerData != null ? PowerMath.quantize(powerData.resonanceBalance(), TELEMETRY_SIG_FIGS) : 0.0;
        int activeLevels = powerData != null ? powerData.activeOxidationLevels() : 0;
        List<Long> levelPower = (powerData != null)
            ? toLevelPowerList(powerData.getEmaPowerByOxidation())
            : List.of(0L, 0L, 0L, 0L);

        if (bestN <= 0) {
            return new LivingWaxedGeneratorData(
                0, 0, 0, 0, 0, coilForm, emaFe, levelEmaFe, domainSnapshots,
                resonanceGain, resonanceBalance, activeLevels, levelPower);
        }
        double eff = PowerMath.tuningEfficiency(
            Math.abs(bestPeriod - pref), pref);
        double unlock = Math.min(1.0, eff * bestN / pref);
        int unlockPermille = (int) Math.round(unlock * 1000);
        int effDeltaSumPermille = (int) Math.round(effDeltaSum * 1000);
        return new LivingWaxedGeneratorData(
            bestPeriod, bestN, unlockPermille, bestDelta, effDeltaSumPermille,
            coilForm, emaFe, levelEmaFe, domainSnapshots,
            resonanceGain, resonanceBalance, activeLevels, levelPower);
    }

    /** double[] (各锈级基础出力 EMA) → List<Long>（RE/t，诊断展示用） */
    private static List<Long> toLevelPowerList(double[] ema) {
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
                d.period(), d.n(), d.maxDelta(), PowerMath.quantize(d.effDeltaSum(), TELEMETRY_SIG_FIGS), deltas));
        }
    }

    /**
     * 发电直存（§3.6 v18 锈级专属通道）：本锈级发电量按「剩余容量比例」
     * 分配入**同锈级**的铜灯堆。
     *
     * <p>隔离只发生在「发电 → 充电」这一跳：k 锈级灯只接收 k 锈级网络的发电
     * （含共振增益）；入灯后仍是通用 FE，放电 / 外部充电无锈级限制。</p>
     *
     * @param generatedRe 本锈级发电量（RE，已含共振增益）
     * @param oxidation   目标锈蚀级（0~3），只分配给 {@code getOxidationLevel(bulb) == oxidation} 的灯堆
     * @return true 表示有铜灯实际充入了电量（需 setChanged 落盘）
     */
    static boolean distributeToBulbs(long generatedRe, int oxidation, List<SlotEntry> entries) {
        long mfe = Math.round(generatedRe * PowerMath.RE_TO_FE * 1000.0);
        if (mfe <= 0) return false;

        record BulbRef(ItemStack stack, int count, long remaining) {}
        List<BulbRef> bulbs = new ArrayList<>();
        long totalRemaining = 0;
        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || !isWaxedBulb(stack.getItem())) continue;
            if (getOxidationLevel(stack.getItem()) != oxidation) continue;   // v18：锈级专属通道
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
            // 优先从运行时缓存读取遥测数据（不影响物品堆叠），回退到 DataComponent
            LivingItemRuntimeData runtimeData = LivingItemClientCache.getCurrentTooltipData();
            LivingWaxedGeneratorData t;
            if (runtimeData.isGenerator()) {
                t = runtimeData.generatorTelemetry();
            } else {
                t = LivingItemManager.getGeneratorData(stack);
            }
            int pref = stack.getCount();
            boolean hasSignal = t.detectedPeriod() > 0;

            // ══ 核心（常显）：最关键的读数 ══
            renderSection(tooltipAdder, "tooltip.livingitem.waxed_copper.section.core");

            // ── 偏好周期 / 宽带 ──
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

            // ── EMA 功率（FE/t）──
            if (t.emaPowerFe() > 0) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.ema_power"))
                    .append(Component.literal(": " + t.emaPowerFe() + " FE/t"))
                    .withStyle(ChatFormatting.YELLOW));
            }

            // ── 网络级共振（容器级，§3.6.1）：收益核心，靠前显示 ──
            renderResonanceTooltip(tooltipAdder, t);

            // ── 状态：无信号时一句「检测中」──
            if (!hasSignal) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.no_signal"))
                    .withStyle(ChatFormatting.DARK_GRAY));
            }

            // ══ 以下为进阶诊断信息，仅 F3+H 高级模式显示 ══
            if (flag.isAdvanced()) {
                renderSection(tooltipAdder, "tooltip.livingitem.waxed_copper.section.settlement");

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
                }

                // ── 共振公式（与发电量公式并列，§3.6.1）──
                renderResonanceFormula(tooltipAdder, t);

                // ── 网络（容器级）：诊断细节，置底 ──
                renderSection(tooltipAdder, "tooltip.livingitem.waxed_copper.section.network");

                if (t.levelEmaPowerFe() > 0) {
                    tooltipAdder.accept(Component.literal("  ")
                        .append(Component.translatable("tooltip.livingitem.waxed_copper.level_power"))
                        .append(Component.literal(": " + t.levelEmaPowerFe() + " FE/t"))
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
        // ── 铜灯电量 + 锈级专属通道（v18）──
        if (isWaxedBulb(item)) {
            int ox = getOxidationLevel(item);
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.bulb_channel",
                    Component.translatable("tooltip.livingitem.waxed_copper.oxidation." + ox)))
                .withStyle(ChatFormatting.GRAY));
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
     * 同一容器内的任一发电机 tooltip 都能看到当前的共振增益 / 平衡度 / 锈级数。</p>
     */
    private static void renderResonanceTooltip(
            java.util.function.Consumer<Component> tooltipAdder,
            LivingWaxedGeneratorData t) {
        int levels = t.activeLevels();
        if (levels >= 2) {
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
                .append(Component.translatable("tooltip.livingitem.waxed_copper.resonance_levels").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" " + levels + "/" + PowerMath.OXIDATION_LEVELS).withStyle(ChatFormatting.GRAY)));
        } else if (levels == 1) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.waxed_copper.resonance_none_single")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        // levels == 0：容器无任何发电，不显示共振行
        // 各锈级出力的具体数值由底部仪器面板的柱状图呈现，此处不再重复文字
    }

    /**
     * 分区分隔线：{@code ─── 小标题 ───────}。
     *
     * <p>用 U+2500 方框绘制横线（已确认在原版字体的位图字形表内，不会渲染成豆腐块）。</p>
     */
    private static void renderSection(java.util.function.Consumer<Component> tooltipAdder, String titleKey) {
        tooltipAdder.accept(Component.literal("  §8─── ")
            .append(Component.translatable(titleKey).withStyle(ChatFormatting.DARK_PURPLE))
            .append(Component.literal(" §8─────────────")));
    }

    /**
     * 共振公式行（§3.6.1），与发电量公式并列展示：
     * {@code 共振 = Σbase × R²   (s=1.00  R=1+3×1.00=4.00)}
     *
     * <p>代入本容器的实际平衡度 s 与锈级数 N，让玩家看懂倍率是怎么算出来的。</p>
     */
    private static void renderResonanceFormula(
            java.util.function.Consumer<Component> tooltipAdder,
            LivingWaxedGeneratorData t) {
        int levels = t.activeLevels();
        if (levels < 1) return;
        double s = t.resonanceBalance();
        double r = 1.0 + (levels - 1) * s;
        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.waxed_copper.resonance_output").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" §7= Σbase × R²  "))
            .append(Component.literal("§8(s=" + String.format("%.2f", s)
                + "  R=1+" + (levels - 1) + "×" + String.format("%.2f", s)
                + "=" + String.format("%.2f", r) + ")")));
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