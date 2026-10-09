package com.qiqi.li.living.domain.power;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData;
import com.qiqi.li.living.domain.power.LivingWaxedChiseledData;
import com.qiqi.li.living.domain.power.LivingWaxedBulbData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import com.qiqi.li.living.util.WaxedCopperFamily;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.api.RedstoneSensor;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
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


    /**
     * 感应诊断开关（-Dlivingitem.debug.sensing=true 启用）：
     * 每 20 tick 打印每台发电机的 4 向入边值 / 跟踪器锁相状态 / 通道状态，
     * 用于定位「游戏内涂蜡发电机不发电」时断点在写边侧还是读侧。
     */
    private static final boolean DEBUG_SENSING = Boolean.getBoolean("livingitem.debug.sensing");

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return WaxedCopperFamily.isWaxedCopperBlock(stack.getItem());
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(
            PowerComponents.LIVING_WAXED_CHISELED_DATA.value(),
            PowerComponents.LIVING_GENERATOR_DATA.value(),
            PowerComponents.LIVING_WAXED_BULB_DATA.value()
        );
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
        // ⚠️ 必须走领域访问器 `ContainerPowerAccess`，**不能**直接 `ctx.getOrCreateContainerData` ——
        // 前者在账本【首次创建】时从 BE 附件回填相位快照（退出重进 / LRU 回收后振荡器相位无缝续接）。
        // 2026-10-09 修复：该调用在 `a52a3ea`（1a-4「容器级数据统一存储」）重构中**静默丢失**，
        // 导致快照「只写（ContainerPhaseWriteback）不读」—— 回填路径长期未生效。
        // 历史：`2c78623` 时链路为 本方法 → TickContext.getOrCreatePowerData → SimpleContainerContext
        // .getOrCreatePowerData() → ContainerLivingItemHandler.getPowerData(this)（含回填）。
        ContainerPowerData powerData = ContainerPowerAccess.getOrCreatePowerData(ctx);
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
        // 相位时间轴（2026-09-11 第三轮修复：坐标系换轴）——优先世界 game time：
        // 跨会话连续（存档持久化，重进继续递增），振荡器物理相位本就由世界 tick
        // 驱动（中继器每 game tick 递减），φ = lastRisingTick mod P 锚在此轴上
        // 重进前后同坐标系——同一布局的 φ 跨会话恒定。旧轴（容器本地 tickCounter）
        // 重进归零，重进后首个真实跳变落位随机 → φ 重锚 → 雕文/切制/格栅派生全变
        // （「修了和没修一样」的教训：前两轮防住了假沿污染，没发现坐标系本身换了）。
        // Level 不可达（纯 Java 单测 / 无 BE 上下文）回退容器本地计数，保持可测性。
        long now = resolvePhaseClock(ctx, powerData);

        // ── 收集发电机（铜灯跳过）──
        Map<Integer, GeneratorState> active = new HashMap<>();
        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || WaxedCopperFamily.isWaxedBulb(stack.getItem())) continue;
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
        // EnergyAccounting.accountEnergy 仍逐发电机调用，用各自 pref 读共享域 —— 逐发电机 pref 敏感保留。

        // 每 tick 每锈级建一次组件 rep 表（≤54 槽，O(N) 可忽略）
        Map<Integer, int[]> repCache = new HashMap<>();
        // 组件标识（锈级 + rep）→ 该组件内的发电机槽位列表
        Map<CopperNetworkTopology.NetworkKey, List<Integer>> groups = new LinkedHashMap<>();

        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            int ox = WaxedCopperFamily.getOxidationLevel(ctx.getItem(genSlot).getItem());
            int rep = CopperNetworkTopology.repOf(ox, genSlot, repCache, ctx, size, containerWidth);
            groups.computeIfAbsent(new CopperNetworkTopology.NetworkKey(ox, rep), k -> new ArrayList<>()).add(genSlot);
        }

        // 每组件只算一次：锚点 BFS + tickCleanup(maxPref)，其余 copyFrom
        for (var en : groups.entrySet()) {
            CopperNetworkTopology.NetworkKey key = en.getKey();
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
        PhaseInterpreter.phaseInterpretation(active, ctx, size, containerWidth, powerData, now);

        // 逐发电机结算能量（用各自 pref 读共享/复制后的 ChannelState；跳变门控见 accountEnergy）
        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            GeneratorState gen = e.getValue();
            ItemStack genStack = ctx.getItem(genSlot);
            int genOxidation = WaxedCopperFamily.getOxidationLevel(genStack.getItem());
            EnergyAccounting.accountEnergy(gen, gen.channel(), gen.preferredPeriod(), baseReByOx, genOxidation, now);
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
            int ox = WaxedCopperFamily.getOxidationLevel(stack.getItem());
            var telemetry = PowerTelemetry.buildTelemetry(gen, stack.getCount(), cf, ox, powerData);
            ContainerRuntimeCache.update(ctx.getContainerKey(), slot,
                RuntimeSegments.EMPTY.with(GeneratorSegment.INSTANCE, telemetry));
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

    // ── 相位时钟（2026-09-11 第三轮修复：坐标系换轴）──

    /**
     * 解析相位时间轴的当前值：世界 game time 优先，容器本地计数回退。
     *
     * <p>φ = lastRisingTick mod P 的锚必须放在<strong>跨会话连续</strong>的时钟上——
     * 世界 game time 随存档持久化、重进继续递增，与振荡器物理相位（中继器
     * delayTimer 每 game tick 递减）同源同轴。容器本地 tickCounter 重进归零，
     * 同一物理跳变在新旧两轴上的读数不同 → φ 重锚 → 三元件派生漂移。</p>
     *
     * <p>回退（Level 不可达）：纯 Java 单测与无 BE 上下文用容器本地计数——
     * 这些环境不跨会话，本地轴的缺陷（重进归零）不触发。</p>
     */
    private static long resolvePhaseClock(ContainerContext ctx, ContainerPowerData powerData) {
        net.minecraft.world.level.Level level = ctx.getLevel();
        if (level != null && !level.isClientSide) {
            return level.getGameTime();
        }
        return powerData.currentTick();
    }

    // ── 铜块网络传播：BFS 驱动（拓扑原语见 {@link CopperNetworkTopology}）──


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
            int chiseledInEdge = CopperNetworkTopology.chiseledInputEdge(ctx, current);

            // 根修（2026-09-09）：首拍无沿——红石账本重建后的首个 calculate，
            // prevEdgeGrid 全零是「历史未知」而非「上一 tick 全 0」，稳态高电平边
            // 全部伪装成 0→S 假上升沿。此时整段跳过边检测（tracker.onRisingEdge
            // 也跳过——旧 warmup 只拦注入不拦跟踪，假沿把回填的锁相负锚覆盖成
            // tick 0，φ 恢复当场被毁，warmup 形同虚设的教训）。回填快照存活到
            // 首个真实跳变，interval = (P-d)-(-d) = P，无缝续接。
            boolean firstFrame = !sensor.hasEdgeHistory();

            if (!firstFrame) {
                // 检查该槽位的边信号（v15 每槽自有出边模型：涂蜡槽绝缘，采样读「入边」
                // = 邻居朝本槽的出边）
                for (int dir = 0; dir < RedstoneSensor.DIRECTIONS; dir++) {
                    if (chiseledInEdge >= 0 && dir != chiseledInEdge) continue;
                    int signal = sensor.sensedSignal(current, dir);
                    int prevSignal = sensor.prevSensedSignal(current, dir);
                    if (signal == prevSignal) continue;
                    int delta = signal - prevSignal;
                    int absDelta = Math.abs(delta);
                    long edgeKey = CopperNetworkTopology.edgeKey(current, dir);
                    if (delta > 0) {
                        SignalTracker tracker = powerData.getOrCreateEdgeTracker(edgeKey);
                        tracker.onRisingEdge(now, absDelta);
                        if (!powerData.inWarmup() && tracker.period() > 0) {
                            channel.onPhaseEvent(new PhaseEvent(
                                (int) edgeKey, tracker.period(),
                                tracker.offset(), absDelta, now), pref);
                        }
                    } else {
                        // 下降沿 → 裂相器（切制）的解读输入：独立命名空间的下降沿跟踪器
                        SignalTracker falling = powerData.getOrCreateEdgeTracker(CopperNetworkTopology.FALLING_BIT | edgeKey);
                        falling.onRisingEdge(now, absDelta);
                    }
                }
            }

            // 注入该槽位注册表中的派生驻波（上一 tick 解读，本 tick 到达上升沿则同权入账）
            // warmup 期间不注入：注册表本就为空（账本重建），防御性双闸门。
            if (!powerData.inWarmup()) {
                for (DerivedPhase dp : powerData.getRegistry(current)) {
                    if (dp.period() > 0 && Math.floorMod(now, dp.period()) == dp.offset()) {
                        channel.onPhaseEvent(new PhaseEvent(
                            PhaseInterpreter.derivedSourceId(current, dp), dp.period(), dp.offset(), dp.delta(), now), pref);
                    }
                }
            }

            // 遍历四方向找同氧化等级的铜块邻居
            for (int dir = 0; dir < RedstoneSensor.DIRECTIONS; dir++) {
                int neighbor = CopperNetworkTopology.traversableNeighbor(ctx, size, containerWidth, current, dir, oxidation);
                if (neighbor < 0) continue;
                if (visited[neighbor]) continue;
                visited[neighbor] = true;
                queue[tail++] = neighbor;
            }
        }
    }


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
            for (int dir = 0; dir < RedstoneSensor.DIRECTIONS; dir++) {
                int v = sensor.sensedSignal(slot, dir);
                SignalTracker t = powerData.getEdgeTracker(((long) slot << 2) | dir);
                edges.append("[dir").append(dir).append("]v=").append(v)
                    .append("/P=").append(t == null ? "-" : String.valueOf(t.period()));
                if (dir < RedstoneSensor.DIRECTIONS - 1) edges.append(' ');
            }
            ChannelState ch = gen.channel();
            int pref = gen.preferredPeriod();
            String inputDirStr = "";
            ItemStack genStack = ctx.getItem(slot);
            if (WaxedCopperFamily.isWaxedChiseled(genStack.getItem())) {
                inputDirStr = " inputDir=" + LivingWaxedChiseledData.of(genStack).inputDir().getSymbol();
            }
            ModLog.CONTAINER.info("[涂蜡感知] {} slot={} pref={} bestP={} n={} Σ√|Δ|={} emaFe={}{} | {}",
                ctx.getContainerKey(), slot, pref,
                ch.bestPeriod(pref), ch.bestN(pref),
                String.format("%.1f", ch.bestEffDeltaSum(pref)),
                gen.getEmaPowerFe(), inputDirStr, edges);
        }
    }

    // ── 相位解读 pass（v19）：委托纯相位解读器 ──

    // ── 遥测构建委托至 PowerTelemetry ──

    /**
     * 发电直存（§3.6 v18 锈级专属通道）：本锈级发电量按「剩余容量比例」
     * 分配入**同锈级**的铜灯堆。
     *
     * <p>隔离只发生在「发电 → 充电」这一跳：k 锈级灯只接收 k 锈级网络的发电
     * （含共振增益）；入灯后仍是通用 FE，放电 / 外部充电无锈级限制。</p>
     *
     * <p>算法（比例分配 + 完整步进保护）已收归 {@link BulbBank}（2026-10-09 power 收口
     * 步骤 2 第 2 步）—— 原先这里是同一份「按剩余容量比例分配」的<b>第二份拷贝</b>
     * （历史上同一个 long 溢出 bug 修完 {@code receive()} 后本方法里还有一份）。</p>
     *
     * @param generatedRe 本锈级发电量（RE，已含共振增益）
     * @param oxidation   目标锈蚀级（0~3），只分配给 {@code WaxedCopperFamily.getOxidationLevel(bulb) == oxidation} 的灯堆
     * @return true 表示有铜灯实际充入了电量（需 setChanged 落盘）
     */
    static boolean distributeToBulbs(long generatedRe, int oxidation, List<SlotEntry> entries) {
        long mfe = Math.round(generatedRe * PowerMath.RE_TO_FE * 1000.0);
        if (mfe <= 0) return false;

        // 与容器充电（FLOOR_WHOLE_FE）的【故意差异】由 FePolicy.ANY_MOVEMENT 承载：
        // 不量化、不做零头回收 —— 发电量已由 RE→mFE 换算取整，够用即可。
        // 锈级专属通道（v18）改由 itemFilter 表达；「是否真有充入」改看 isDirty()
        // （与原先 `distributed > 0` 等价：dirty 只在真的 set 过时置位）。
        BulbBank bank = BulbBank.scanEntries(entries,
            stack -> WaxedCopperFamily.getOxidationLevel(stack.getItem()) == oxidation);
        bank.deposit(mfe, false, BulbBank.FePolicy.ANY_MOVEMENT);
        return bank.isDirty();
    }


    // ── Tooltip ──
    // 2026-10-09 power 收口 步骤 4 第 1 刀：渲染逻辑抽到 {@link LivingWaxedCopperTooltip}
    // （原 ~283 行 → 本类只留一行委托）。顺带删除两个死方法
    // （renderResonanceTooltip / renderResonanceFormula —— 全库仅声明、零调用）
    // 与 addToTooltip 开头未使用的局部变量 oxidation。

    @Override
    public void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag,
                             ItemStack stack) {
        LivingWaxedCopperTooltip.append(context, tooltipAdder, flag, stack);
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
        if (!WaxedCopperFamily.isWaxedChiseled(stack.getItem())) return false;
        var data = LivingWaxedChiseledData.of(stack);
        if ("input".equals(slotName)) {
            LivingWaxedChiseledData.set(stack, data.withInputDir(direction));
            return true;
        }
        return false;
    }

    // ── 静态工具方法 ──

    /** 从物品取线圈形态（用于 telemetry 和 tooltip 显示） */
    static int getCoilForm(Item item) {
        if (WaxedCopperFamily.isWaxedChiseled(item)) return LivingWaxedGeneratorData.FORM_CHISELED;
        if (WaxedCopperFamily.isWaxedCut(item)) return LivingWaxedGeneratorData.FORM_CUT;
        if (WaxedCopperFamily.isWaxedGrate(item)) return LivingWaxedGeneratorData.FORM_GRATE;
        return LivingWaxedGeneratorData.FORM_BLOCK;
    }

}