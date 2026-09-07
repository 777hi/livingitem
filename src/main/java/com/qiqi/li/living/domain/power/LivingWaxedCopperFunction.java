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
import com.qiqi.li.living.domain.redstone.RedstoneSensor;
import com.qiqi.li.living.domain.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData.DomainSnapshot;
import com.qiqi.li.logging.ModLog;

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

    /**
     * 感应诊断开关（-Dlivingitem.debug.sensing=true 启用）：
     * 每 20 tick 打印每台发电机的 4 向入边值 / 跟踪器锁相状态 / 通道状态，
     * 用于定位「游戏内涂蜡发电机不发电」时断点在写边侧还是读侧。
     */
    private static final boolean DEBUG_SENSING = Boolean.getBoolean("livingitem.debug.sensing");

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

        RedstoneSensor sensor = tick.getSensor(ctx);
        if (sensor == null) {
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
        // 组件 = (锈级, rep)（v19：连通性唯一维度 = 氧化等级，形态个性全部迁移到解读规则），
        // 每组件仅锚点跑一次 runBfs，其余发电机 copyFrom 锚点的 ChannelState
        // （相位历史随之同步，O(域) 极廉价）。
        // accountEnergy 仍逐发电机调用，用各自 pref 读共享域 —— 逐发电机 pref 敏感保留。

        // 每 tick 每锈级建一次组件 rep 表（≤54 槽，O(N) 可忽略）
        Map<Integer, int[]> repCache = new HashMap<>();
        // 组件标识（锈级 + rep）→ 该组件内的发电机槽位列表
        Map<NetworkKey, List<Integer>> groups = new LinkedHashMap<>();

        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            int ox = getOxidationLevel(ctx.getItem(genSlot).getItem());
            int rep = repOf(ox, genSlot, repCache, ctx, size, containerWidth);
            groups.computeIfAbsent(new NetworkKey(ox, rep), k -> new ArrayList<>()).add(genSlot);
        }

        // 每组件只算一次：锚点 BFS + tickCleanup(maxPref)，其余 copyFrom
        for (var en : groups.entrySet()) {
            NetworkKey key = en.getKey();
            List<Integer> members = en.getValue();
            int anchorSlot = members.get(0);
            GeneratorState anchor = active.get(anchorSlot);
            ChannelState shared = anchor.channel();
            int maxPref = 0;
            for (int s : members) maxPref = Math.max(maxPref, active.get(s).preferredPeriod());

            runBfs(anchorSlot, key.oxidation(), shared, anchor.preferredPeriod(),
                powerData, sensor, ctx, size, containerWidth, now);
            shared.tickCleanup(now, maxPref);
            for (int i = 1; i < members.size(); i++) {
                active.get(members.get(i)).channel().copyFrom(shared);
            }
        }

        // ── 相位解读 pass（v19）：三形态元件解读输入信号，派生相位写注册表 ──
        // 置于结算之前：解读注入的驻波上升沿（下一 tick 经 BFS 注入）与真实采样同权入账。
        phaseInterpretation(active, ctx, size, containerWidth, powerData, now);

        // 逐发电机结算能量（用各自 pref 读共享/复制后的 ChannelState；跳变门控见 accountEnergy）
        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            GeneratorState gen = e.getValue();
            ItemStack genStack = ctx.getItem(genSlot);
            int genOxidation = getOxidationLevel(genStack.getItem());
            accountEnergy(gen, gen.channel(), gen.preferredPeriod(), baseReByOx, genOxidation, now);
        }

        if (DEBUG_SENSING) {
            debugLogSensing(ctx, sensor, powerData, active, now);
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
        // v19.1：值比较而非引用比较——Pos2D 经序列化/反序列化后是值相等的新实例，
        // 引用比较会让配置过的方向全部落入 fallback（恒 UP）。
        if (dir.x() == 0) {
            return dir.y() < 0 ? ContainerRedstoneData.EDGE_UP : ContainerRedstoneData.EDGE_DOWN;
        }
        return dir.x() < 0 ? ContainerRedstoneData.EDGE_LEFT : ContainerRedstoneData.EDGE_RIGHT;
    }

    /**
     * 单次 BFS：从发电机槽位出发，遍历同氧化等级铜块网络，
     * 检测边信号上升/下降沿并注入到 {@code channel}，同时注入访问槽位的派生驻波。
     *
     * <p>v19（拓扑统一 + 相位解读）：网络连通性只由氧化等级决定（形态不再约束
     * 连通与采样方向——三形态的个性全部迁移到「解读规则」上）；下降沿喂入独立的
     * 裂相跟踪器（切制解读用）；派生驻波按 {@code now ≡ offset (mod P)} 视为上升沿。</p>
     *
     * @param genSlot  发电机所在槽位（作为 BFS 起点；同组件任意槽位可达性相同）
     * @param oxidation 锈蚀等级（网络连通性唯一维度）
     * @param channel  目标通道
     * @param pref     偏好周期（onPhaseEvent 实际忽略 pref，域仅按 period 分桶）
     * @param powerData 容器电力数据（持久化 tracker + 派生注册表）
     * @param redstone 红石数据（边信号双缓冲）
     * @param ctx      容器上下文
     * @param size     容器总槽位数
     * @param containerWidth 容器宽度（列数）
     * @param now      当前 tick
     */
    private static void runBfs(
            int genSlot, int oxidation, ChannelState channel, int pref,
            ContainerPowerData powerData, RedstoneSensor sensor,
            ContainerContext ctx, int size, int containerWidth, long now) {

        boolean[] visited = new boolean[size];
        int[] queue = new int[size];
        int head = 0, tail = 0;
        queue[tail++] = genSlot;
        visited[genSlot] = true;

        while (head < tail) {
            int current = queue[head++];

            // 雕文 = 移相器（v19.1）：发电采样面与信号层二极管镜像——只感应输入方向。
            // 相位解读（interpretShifter）也只读输入方向，两层语义一致。
            int chiseledInEdge = chiseledInputEdge(ctx, current);

            // 检查该槽位的边信号（v15 每槽自有出边模型：涂蜡槽绝缘，采样读「入边」
            // = 邻居朝本槽的出边）
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                if (chiseledInEdge >= 0 && dir != chiseledInEdge) continue;
                int signal = sensor.sensedSignal(current, dir);
                int prevSignal = sensor.prevSensedSignal(current, dir);
                if (signal == prevSignal) continue;
                int delta = signal - prevSignal;
                int absDelta = Math.abs(delta);
                long edgeKey = edgeKey(current, dir);
                if (delta > 0) {
                    SignalTracker tracker = powerData.getOrCreateEdgeTracker(edgeKey);
                    tracker.onRisingEdge(now, absDelta);
                    if (tracker.period() > 0) {
                        channel.onPhaseEvent(new PhaseEvent(
                            (int) edgeKey, tracker.period(),
                            tracker.offset(), absDelta, now), pref);
                    }
                } else {
                    // 下降沿 → 裂相器（切制）的解读输入：独立命名空间的下降沿跟踪器
                    SignalTracker falling = powerData.getOrCreateEdgeTracker(FALLING_BIT | edgeKey);
                    falling.onRisingEdge(now, absDelta);
                }
            }

            // 注入该槽位注册表中的派生驻波（上一 tick 解读，本 tick 到达上升沿则同权入账）
            for (DerivedPhase dp : powerData.getRegistry(current)) {
                if (dp.period() > 0 && Math.floorMod(now, dp.period()) == dp.offset()) {
                    channel.onPhaseEvent(new PhaseEvent(
                        derivedSourceId(current, dp), dp.period(), dp.offset(), dp.delta(), now), pref);
                }
            }

            // 遍历四方向找同氧化等级的铜块邻居
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                int neighbor = traversableNeighbor(ctx, size, containerWidth, current, dir, oxidation);
                if (neighbor < 0) continue;
                if (visited[neighbor]) continue;
                visited[neighbor] = true;
                queue[tail++] = neighbor;
            }
        }
    }

    /**
     * 从 current 沿 dir 是否可达同氧化级铜块邻居；可达返回邻居槽位，否则 -1。
     * v19：网络连通性只由氧化等级决定（形态不再约束连通——个性迁移到解读规则）。
     * runBfs 与组件分组共用，避免两套邻接逻辑分叉。
     */
    private static int traversableNeighbor(ContainerContext ctx, int size, int width,
            int current, int dir, int oxidation) {
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
        if (getOxidationLevel(ns.getItem()) != oxidation) return -1;
        return neighbor;
    }

    /**
     * 每 tick 每锈级建一次组件 rep 表；返回 slot 所属组件 rep（组件内最小槽位）。
     * rep 稳定（= 最小铜块槽位），保证跨 tick 同一网络映射到同一 ChannelState 实例。
     */
    private static int repOf(int oxidation, int slot, Map<Integer, int[]> cache,
            ContainerContext ctx, int size, int width) {
        int[] arr = cache.get(oxidation);
        if (arr == null) {
            arr = new int[size];
            Arrays.fill(arr, -1);
            int[] comp = new int[size];
            int[] q = new int[size];
            for (int s = 0; s < size; s++) {
                if (arr[s] != -1) continue;
                ItemStack st = ctx.getItem(s);
                if (st.isEmpty() || !isWaxedCopperBlock(st.getItem()) || isWaxedBulb(st.getItem())) continue;
                if (getOxidationLevel(st.getItem()) != oxidation) continue;
                // 收集该组件全部槽位（弱连通）
                int cn = 0, h = 0, t = 0;
                q[t++] = s; arr[s] = s; comp[cn++] = s;
                while (h < t) {
                    int cur = q[h++];
                    for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                        int nb = traversableNeighbor(ctx, size, width, cur, dir, oxidation);
                        if (nb < 0 || arr[nb] != -1) continue;
                        arr[nb] = s; comp[cn++] = nb; q[t++] = nb;
                    }
                }
                int rep = s;
                for (int i = 0; i < cn; i++) rep = Math.min(rep, comp[i]);
                for (int i = 0; i < cn; i++) arr[comp[i]] = rep;
            }
            cache.put(oxidation, arr);
        }
        return arr[slot];
    }

    /** 组件标识：氧化等级 + 组件内最小铜块槽位（v19：连通性唯一维度 = 氧化等级） */
    private record NetworkKey(int oxidation, int rep) {}

    /** 边跟踪器键：(slot << 2) | dir */
    private static long edgeKey(int slot, int dir) {
        return ((long) slot << 2) | dir;
    }

    /**
     * 雕文槽位的发电采样方向（仅感应输入方向，镜像信号层二极管语义）；非雕文返回 -1（全向）。
     */
    /** 测试可见：Pos2D → 边方向的映射（值比较，序列化实例安全） */
    static int chiseledInputEdgeForTest(Pos2D dir) {
        return pos2dToEdgeDir(dir);
    }

    private static int chiseledInputEdge(ContainerContext ctx, int slot) {
        ItemStack stack = ctx.getItem(slot);
        if (stack.isEmpty() || !isWaxedChiseled(stack.getItem())) return -1;
        return pos2dToEdgeDir(LivingItemManager.getWaxedChiseledData(stack).inputDir());
    }

    /** 下降沿跟踪器命名空间位（与上升沿跟踪器同表，位隔离） */
    private static final long FALLING_BIT = 1L << 32;

    /**
     * 感应诊断日志（-Dlivingitem.debug.sensing=true）：每 20 tick 打印每台发电机的
     * 4 向入边值、各边跟踪器锁相状态、通道最佳域与 EMA——用于在真实游戏里区分
     * 「入边恒 0（信号层没写到涂蜡邻居的出边）」还是「入边振荡但通道不锁相」。
     */
    private static void debugLogSensing(ContainerContext ctx, RedstoneSensor sensor,
            ContainerPowerData powerData, Map<Integer, GeneratorState> active, long now) {
        if (now % 20 != 0) return;
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            GeneratorState gen = e.getValue();
            StringBuilder edges = new StringBuilder();
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                int v = sensor.sensedSignal(slot, dir);
                SignalTracker t = powerData.getEdgeTracker(((long) slot << 2) | dir);
                edges.append("[dir").append(dir).append("]v=").append(v)
                    .append("/P=").append(t == null ? "-" : String.valueOf(t.period()));
                if (dir < ContainerRedstoneData.EDGE_COUNT - 1) edges.append(' ');
            }
            ChannelState ch = gen.channel();
            int pref = gen.preferredPeriod();
            String inputDirStr = "";
            ItemStack genStack = ctx.getItem(slot);
            if (isWaxedChiseled(genStack.getItem())) {
                inputDirStr = " inputDir=" + LivingItemManager.getWaxedChiseledData(genStack).inputDir().getSymbol();
            }
            ModLog.CONTAINER.info("[涂蜡感知] {} slot={} pref={} bestP={} n={} Σ√|Δ|={} emaFe={}{} | {}",
                ctx.getContainerKey(), slot, pref,
                ch.bestPeriod(pref), ch.bestN(pref),
                String.format("%.1f", ch.bestEffDeltaSum(pref)),
                gen.getEmaPowerFe(), inputDirStr, edges);
        }
    }

    // ── 相位解读 pass（v19 相位解读三元件）──
    /**
     * 三形态相位元件各解读输入信号，派生相位写注册表。
     *
     * <p>解读是「驻波」语义：输入 = 稳定的周期波形（边跟踪器的锁相状态 /
     * 邻居注册表的驻波条目），派生输出同样是驻波——每 tick 重算自己的条目；
     * 注入侧在 {@code now ≡ offset (mod P)} 的 tick 视为上升沿入账。</p>
     *
     * <p><b>组合与防环（结构性，无检测代码）</b>：元件间组合只经移相链
     * （雕文读输入方向邻居的注册表）；加法器只读自己的真实边（不读注册表）、
     * 裂相器无外部输入。因此依赖图是「链」而非「环」——移相环的每个成员的
     * 输入边都是蜡-蜡死边（无种子），注册表永远为空，环自熄；不存在
     * 「互读导致偏移每 tick 自增跑满」的通路。</p>
     *
     * <p><b>活性</b>：输入源停跳超过 {@link PowerMath#aliveWindow} 后，
     * 对应解读停止、驻波经 {@link ContainerPowerData#pruneRegistry} 修剪——死源不发电。</p>
     *
     * <p><b>两阶段提交</b>：先全部算入草稿、再统一写回——解读过程中读取的
     * 邻居注册表一律是上一 tick 的状态，与槽位处理顺序无关。</p>
     */
    private static void phaseInterpretation(Map<Integer, GeneratorState> active,
            ContainerContext ctx, int size, int width, ContainerPowerData powerData, long now) {
        powerData.pruneRegistry(now);

        Map<Integer, List<DerivedPhase>> draft = new HashMap<>();
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            ItemStack stack = ctx.getItem(slot);
            int cf = getCoilForm(stack.getItem());
            switch (cf) {
                case LivingWaxedGeneratorData.FORM_CHISELED ->
                    interpretShifter(slot, stack, ctx, size, width, powerData, now, draft);
                case LivingWaxedGeneratorData.FORM_CUT ->
                    interpretSplitter(slot, powerData, now, draft);
                case LivingWaxedGeneratorData.FORM_GRATE ->
                    interpretAdder(slot, powerData, now, draft);
                default -> { } // 基座铜块 / 铜灯：只会「读」不会「造」
            }
        }
        for (var e : draft.entrySet()) {
            powerData.setRegistry(e.getKey(), e.getValue());
        }
    }

    /**
     * 雕文 = 移相器：读信号的「位置」。
     *
     * <p>解读规则：对输入方向上的每一路锁相波形 (P, φ, δ)（真实边 + 输入方向
     * 邻居的注册表驻波），派生 (P, (φ+1) mod P, δ)——驻波整体延迟 1 tick。
     * k 台首尾相连（后者的输入方向指向前者）= 任意偏移延迟线，解锁奇数偏移制造
     * （中继器延迟全是偶数 tick）。环自熄：环上成员的输入边都是蜡-蜡死边，无种子。</p>
     */
    private static void interpretShifter(int slot, ItemStack stack, ContainerContext ctx,
            int size, int width, ContainerPowerData powerData, long now,
            Map<Integer, List<DerivedPhase>> draft) {
        var data = LivingItemManager.getWaxedChiseledData(stack);
        int inEdge = pos2dToEdgeDir(data.inputDir());
        List<DerivedPhase> out = new ArrayList<>();

        // 输入一：输入方向边上的真实波形（锁相状态，活性窗口内）
        SignalTracker t = powerData.getEdgeTracker(edgeKey(slot, inEdge));
        if (t != null && t.period() > 0
                && now - t.lastRisingTick() <= PowerMath.aliveWindow(t.period())) {
            out.add(new DerivedPhase(t.period(), Math.floorMod(t.offset() + 1, t.period()),
                t.lastDelta(), DerivedPhase.KIND_SHIFT, now));
        }

        // 输入二：输入方向邻居的注册表驻波（移相链的组合入口）
        int neighbor = ContainerContext.resolveNeighbor(slot, inEdge, size, width);
        if (neighbor >= 0) {
            for (DerivedPhase dp : powerData.getRegistry(neighbor)) {
                out.add(new DerivedPhase(dp.period(), Math.floorMod(dp.offset() + 1, dp.period()),
                    dp.delta(), DerivedPhase.KIND_SHIFT, now));
            }
        }

        if (!out.isEmpty()) draft.put(slot, out);
    }

    /**
     * 切制 = 裂相器：读信号的「另一半」。
     *
     * <p>解读规则：每条边的下降沿波形（独立跟踪器）直接登记为派生相位——
     * 一个方波贡献 2 个反相相位（上升沿 φ 由全网真实采样、下降沿 φ_f 由裂相器
     * 补齐）。非对称波形的 φ_f ≠ φ + P/2（涌现）。P=2 时钟 + 1 台切制 → n=2 满相。
     * 裂相不是延迟：偏移取下降沿自身位置，从下一个周期起注入。</p>
     */
    private static void interpretSplitter(int slot, ContainerPowerData powerData, long now,
            Map<Integer, List<DerivedPhase>> draft) {
        List<DerivedPhase> out = new ArrayList<>();
        for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
            SignalTracker t = powerData.getEdgeTracker(FALLING_BIT | edgeKey(slot, dir));
            if (t != null && t.period() > 0
                    && now - t.lastRisingTick() <= PowerMath.aliveWindow(t.period())) {
                out.add(new DerivedPhase(t.period(), t.offset(),
                    t.lastDelta(), DerivedPhase.KIND_SPLIT, now));
            }
        }
        if (!out.isEmpty()) draft.put(slot, out);
    }

    /**
     * 格栅 = 相位加法器：读信号间的「关系」。
     *
     * <p>解读规则：汇集 4 条边的真实锁相波形，按周期分桶；对含 ≥2 路的桶派生
     * (P, Σφᵢ mod P, min δᵢ)——信号层「多路幅度求和」的电力层镜像（相位求和）。
     * 去重诚实：和已存在于域内则无增益；同相位双输入 → 2φ「翻倍」可算。</p>
     *
     * <p>v1 不读注册表（组合经移相链实现）：加法器若互读邻居驻波，两只对摆且
     * 各有真实输入时会互相把对方的和吸进自己的和，偏移沿加法子群逐 tick 自增
     * 跑满——结构上掐断这条唯一的成环通路。</p>
     */
    private static void interpretAdder(int slot, ContainerPowerData powerData, long now,
            Map<Integer, List<DerivedPhase>> draft) {
        Map<Integer, List<int[]>> byPeriod = new HashMap<>(); // period → [offset, delta]
        for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
            SignalTracker t = powerData.getEdgeTracker(edgeKey(slot, dir));
            if (t == null || t.period() <= 0) continue;
            if (now - t.lastRisingTick() > PowerMath.aliveWindow(t.period())) continue;
            byPeriod.computeIfAbsent(t.period(), k -> new ArrayList<>())
                .add(new int[] {t.offset(), t.lastDelta()});
        }
        List<DerivedPhase> out = new ArrayList<>();
        for (var e : byPeriod.entrySet()) {
            List<int[]> phases = e.getValue();
            if (phases.size() < 2) continue; // 单路无可加
            int period = e.getKey();
            int sum = 0;
            int minDelta = Integer.MAX_VALUE;
            for (int[] p : phases) {
                sum = Math.floorMod(sum + p[0], period);
                minDelta = Math.min(minDelta, p[1]);
            }
            out.add(new DerivedPhase(period, sum, minDelta, DerivedPhase.KIND_ADD, now));
        }
        if (!out.isEmpty()) draft.put(slot, out);
    }

    /** 派生驻波的事件源 id（仅信息用途：域按周期分桶、偏移去重） */
    private static int derivedSourceId(int slot, DerivedPhase dp) {
        return (slot << 3) | dp.kind();
    }

    /**
     * 从通道最佳域计算能量并记入发电机（v18：容器级无总账，改走 baseReByOx 按锈级累加）。
     *
     * <p><b>跳变门控（v19）</b>：只从「本 tick 有跳变」的最佳域入账，
     * 能量 = 合因子 × P × 本 tick 跳变路数。回归「跳变即能量事件」——
     * 域活着但本 tick 无上升沿 → 产出 0。修复旧「每 tick 无条件入账合因子×P」的
     * 两个问题：① 平均功率 = 合因子×P 随周期线性增长，慢时钟无代价碾压快时钟
     * （频率中性化反转，整数倍谐波调谐效率恰为 1.0 时可无限放大）；
     * ② 振荡器停机后域存活窗口（max(32, 2×P) tick）内照常白拿发电量。</p>
     *
     * <p>同时按锈蚟级累加**基础**出力到 {@code baseReByOx}，供网络级共振与
     * 锈级功率读数使用（见 living-power-tech.md §3.7）。这里累的是共振前的值——
     * 共振增益只在 tick 末统一套用一次，绝不回灌。</p>
     *
     * @param baseReByOx 各锈蚟级基础出力累加器（可为 null，表示不统计共振）
     * @param oxidation  该发电机所属锈蚟级（0~3）
     * @param now        当前 tick（跳变门控的判定基准）
     */
    static void accountEnergy(GeneratorState gen, ChannelState channel, int pref,
                                      long[] baseReByOx, int oxidation, long now) {
        ChannelState.PhaseDomain active = channel.bestActiveDomain(pref, now);
        if (active == null) return;
        double factor = channel.factorOf(active, pref);
        int period = active.period();
        if (factor > 0 && period > 0) {
            long re = PowerMath.eventEnergyRe(factor, period) * active.jumpCount(now);
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

        /** 最近一次上升沿的 tick（-1 = 尚无）；派生解读的活性判定用（v19） */
        public long lastRisingTick() {
            return lastRisingTick;
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

        // 全部域快照（F3+H 显示用；v19 单通道——切制双通道已随相位解读重构退役）
        List<DomainSnapshot> domainSnapshots = new ArrayList<>();
        collectDomains(channel, domainSnapshots);

        // 每个发电机独立显示均值功率（窗口均值，无逐 tick 纹波；毫 FE 定点）+ 本锈级显示功率
        long emaFe = gen.getDisplayEmaPowerMilliFe();
        long levelEmaFe = powerData != null ? powerData.getLevelDisplayEmaPowerMilliFe(oxidation) : 0;

        // 网络级共振（容器级，§3.6.1）：只读 powerData 的 EMA 基础值，绝不回灌
        double resonanceGain = powerData != null ? PowerMath.quantize(powerData.resonanceGain(), TELEMETRY_SIG_FIGS) : 1.0;
        double resonanceBalance = powerData != null ? PowerMath.quantize(powerData.resonanceBalance(), TELEMETRY_SIG_FIGS) : 0.0;
        int activeLevels = powerData != null ? powerData.activeOxidationLevels() : 0;
        List<Long> levelPower = (powerData != null)
            ? toLevelPowerMilliFeList(powerData.getDisplayEmaByOxidationMilliFe())
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

    /** long[]（各锈级显示均值功率，毫 FE 定点）→ List<Long>（锈级柱状图数据源） */
    private static List<Long> toLevelPowerMilliFeList(long[] milliFe) {
        List<Long> out = new ArrayList<>(milliFe.length);
        for (long v : milliFe) out.add(v);
        return out;
    }

    /** 毫 FE 定点 → 人类可读功率串（≥1 FE 显示整数，否则两位小数） */
    static String formatMilliFe(long milliFe) {
        return milliFe >= 1000
            ? String.valueOf(milliFe / 1000)
            : String.format("%.2f", milliFe / 1000.0);
    }

    /**
     * 收集通道的全部域快照到 list。
     *
     * <p>相位必须走 {@code phasesSorted()} 而非 {@code deltaByOffset().values()}：
     * 后者是 HashMap 的值视图，顺序为哈希序，且会丢掉 offset 本身——
     * 客户端因此拿不到相位位置，相位圆盘无从画起。</p>
     */
    private static void collectDomains(ChannelState ch, List<DomainSnapshot> out) {
        for (var e : ch.domains().entrySet()) {
            ChannelState.PhaseDomain d = e.getValue();
            List<Integer> offsets = new ArrayList<>();
            List<Integer> deltas = new ArrayList<>();
            for (var phase : d.phasesSorted()) {
                offsets.add(phase.getKey());
                deltas.add(phase.getValue());
            }
            out.add(new DomainSnapshot(
                d.period(), d.n(), d.maxDelta(), PowerMath.quantize(d.effDeltaSum(), TELEMETRY_SIG_FIGS),
                offsets, deltas));
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

        // ── 形态功能说明（三变体差异）──
        tooltipAdder.accept(Component.translatable(formDescKey(item))
            .withStyle(ChatFormatting.GRAY));

        // ── 雕文感应方向（仅涂蜡雕文；v19.1 只感应输入方向）──
        if (isWaxedChiseled(item)) {
            var chiseledData = LivingItemManager.getWaxedChiseledData(stack);
            tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.waxed_copper.chiseled_dir",
                    chiseledData.inputDir().getSymbol())
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
            if (t.emaPowerMilliFe() > 0) {
                tooltipAdder.accept(Component.literal("  ")
                    .append(Component.translatable("tooltip.livingitem.waxed_copper.ema_power"))
                    .append(Component.literal(": " + formatMilliFe(t.emaPowerMilliFe()) + " FE/t"))
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

                if (t.levelEmaPowerMilliFe() > 0) {
                    tooltipAdder.accept(Component.literal("  ")
                        .append(Component.translatable("tooltip.livingitem.waxed_copper.level_power"))
                        .append(Component.literal(": " + formatMilliFe(t.levelEmaPowerMilliFe()) + " FE/t"))
                        .withStyle(ChatFormatting.DARK_GRAY));
                }
                // 各域快照（紧凑格式）
                for (var ds : t.domains()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  §5P=").append(ds.period()).append("t")
                      .append("  n=").append(ds.n())
                      .append("  Σ√|Δ|=").append(String.format("%.1f", ds.effDeltaSum()))
                      .append("  max|Δ|=").append(ds.maxDelta());
                    int gap = ds.minPhaseGap();
                    if (gap >= 0) sb.append("  Δφ=").append(gap).append("t");
                    tooltipAdder.accept(Component.literal(sb.toString())
                        .withStyle(ChatFormatting.DARK_PURPLE));
                    // 各相位「偏移 → |Δ|」配对（按 offset 升序；圆盘读不出精确间距时看这行）
                    if (!ds.deltas().isEmpty()) {
                        StringBuilder sb2 = new StringBuilder("    ");
                        List<Integer> offs = ds.offsets();
                        for (int i = 0; i < ds.deltas().size(); i++) {
                            int off = (offs != null && i < offs.size()) ? offs.get(i) : i;
                            sb2.append("φ").append(off).append(":").append(ds.deltas().get(i)).append(" ");
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

    /** 从物品取形态功能说明翻译键（三变体差异说明，v19.1） */
    private static String formDescKey(Item item) {
        if (isWaxedChiseled(item)) return "tooltip.livingitem.waxed_copper.form_desc.chiseled";
        if (isWaxedCut(item)) return "tooltip.livingitem.waxed_copper.form_desc.cut";
        if (isWaxedGrate(item)) return "tooltip.livingitem.waxed_copper.form_desc.grate";
        return "tooltip.livingitem.waxed_copper.form_desc.block";
    }

    /** 从物品取形态翻译键 */
    private static String formTranslationKey(Item item) {
        if (isWaxedChiseled(item)) return "tooltip.livingitem.waxed_copper.form.chiseled";
        if (isWaxedCut(item)) return "tooltip.livingitem.waxed_copper.form.cut";
        if (isWaxedGrate(item)) return "tooltip.livingitem.waxed_copper.form.grate";
        return "tooltip.livingitem.waxed_copper.form.block";
    }

    // ── WASD 方向配置（涂蜡雕文的输入/输出方向，信号层同款 2 键配置）──

    private static final String[] CHISELED_SLOT_NAMES = {"input"};

    @Override
    public int getDirectionKeyCount() {
        return 1;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return CHISELED_SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (!isWaxedChiseled(stack.getItem())) return false;
        var data = LivingItemManager.getWaxedChiseledData(stack);
        if ("input".equals(slotName)) {
            LivingItemManager.setWaxedChiseledData(stack, data.withInputDir(direction));
            return true;
        }
        return false;
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