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

        ContainerRedstoneData redstone = tick.getOrCreateRedstoneData(ctx);
        if (redstone == null) {
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
            powerData.endTick(0);
            return;
        }

        // ── 铜块网络传播：每台发电机 BFS 遍历同氧化等级铜块网络 ──
        // 边信号跟踪器持久化在 ContainerPowerData 中（跨 tick 跟踪周期）

        for (var e : active.entrySet()) {
            int genSlot = e.getKey();
            GeneratorState gen = e.getValue();
            ChannelState channel = gen.channel();
            int pref = gen.preferredPeriod();

            // 获取发电机的氧化等级
            ItemStack genStack = ctx.getItem(genSlot);
            int genOxidation = getOxidationLevel(genStack.getItem());

            // BFS：找同氧化等级网络中所有有边信号的槽位
            Set<Integer> visited = new HashSet<>();
            Queue<Integer> queue = new LinkedList<>();
            queue.add(genSlot);
            visited.add(genSlot);

            while (!queue.isEmpty()) {
                int current = queue.poll();
                int row = current / containerWidth;
                int col = current % containerWidth;

                // 检查该槽位的 4 条边
                for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                    int signal = redstone.getEdgeValue(current, dir);
                    int prevSignal = redstone.getPrevEdgeValue(current, dir);
                    if (signal == prevSignal) continue;

                    int delta = signal - prevSignal;
                    int absDelta = Math.abs(delta);

                    if (delta > 0) {
                        // 上升沿：从持久化 tracker 获取周期信息
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
                int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                for (int[] d : dirs) {
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

            channel.tickCleanup(now, pref);

            // ── 能量入账：每 tick 每发电机一次，从最佳域计算 ──
            double factor = channel.bestFactor(pref);
            int period = channel.bestPeriod(pref);
            if (factor > 0 && period > 0) {
                long re = PowerMath.eventEnergyRe(factor, period);
                if (re > 0) {
                    gen.onEventEnergy(re);          // per-generator EMA
                    powerData.onEventEnergy(re);    // container total for distribution
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

        // ── 发电直存（§3.6 v17.5）──
        long generatedRe = powerData.drainGeneratedRe();
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

        // 全部域快照（F3+H 显示用）
        List<DomainSnapshot> domainSnapshots = new ArrayList<>();
        for (var e : channel.domains().entrySet()) {
            ChannelState.PhaseDomain d = e.getValue();
            List<Integer> deltas = new ArrayList<>(d.deltaByOffset().values());
            domainSnapshots.add(new DomainSnapshot(
                d.period(), d.n(), d.maxDelta(), d.effDeltaSum(), deltas));
        }

        // 每个发电机独立 EMA 功率 + 容器总功率
        long emaFe = gen.getEmaPowerFe();
        long containerEmaFe = powerData != null ? powerData.getEmaPowerFe() : 0;

        if (bestN <= 0) {
            return new LivingWaxedGeneratorData(
                0, 0, 0, 0, 0, coilForm, emaFe, containerEmaFe, domainSnapshots);
        }
        double eff = PowerMath.tuningEfficiency(
            Math.abs(bestPeriod - pref), pref);
        double unlock = Math.min(1.0, eff * bestN / pref);
        int unlockPermille = (int) Math.round(unlock * 1000);
        int effDeltaSumPermille = (int) Math.round(effDeltaSum * 1000);
        return new LivingWaxedGeneratorData(
            bestPeriod, bestN, unlockPermille, bestDelta, effDeltaSumPermille,
            coilForm, emaFe, containerEmaFe, domainSnapshots);
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

        // ── 耦合管径 ──
        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.waxed_copper.coupling"))
            .append(Component.literal(": " + String.format("%.2f", PowerMath.coupling(oxidation))))
            .withStyle(ChatFormatting.GRAY));

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

    /** 从物品取形态翻译键 */
    private static String formTranslationKey(Item item) {
        if (isWaxedChiseled(item)) return "tooltip.livingitem.waxed_copper.form.chiseled";
        if (isWaxedCut(item)) return "tooltip.livingitem.waxed_copper.form.cut";
        if (isWaxedGrate(item)) return "tooltip.livingitem.waxed_copper.form.grate";
        return "tooltip.livingitem.waxed_copper.form.block";
    }

    // ── WASD 方向配置（涂蜡切制的感应方向，v3 保留骨架）──

    private static final String[] CUT_SLOT_NAMES = {"sense"};

    @Override
    public int getDirectionKeyCount() {
        return 1;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return CUT_SLOT_NAMES;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (!"sense".equals(slotName) || !isWaxedCut(stack.getItem())) return false;
        LivingItemManager.setWaxedCutData(stack, new LivingWaxedCutData(direction));
        return true;
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

    /** 涂蜡雕文（2 线圈 H/V 隔离） */
    public static boolean isWaxedChiseled(Item item) {
        return item == Items.WAXED_CHISELED_COPPER || item == Items.WAXED_EXPOSED_CHISELED_COPPER
            || item == Items.WAXED_WEATHERED_CHISELED_COPPER || item == Items.WAXED_OXIDIZED_CHISELED_COPPER;
    }

    /** 涂蜡切制（1 线圈 × 1 向，无干扰） */
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

    /** 锈蚀档位 0~3（决定耦合管径，见 {@link PowerMath#coupling}） */
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