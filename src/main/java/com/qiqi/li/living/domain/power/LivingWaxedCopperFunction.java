package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

/**
 * 活涂蜡铜块 —— 电力层发电机 / 电池（§3.4、§3.5、§3.6）。
 *
 * <p>涂蜡 = 绝缘 = 不参与信号层；通过感应邻居红电信号的<b>变化</b>发电。
 * 锈蚀四档 = 感应耦合管径（{@link PowerMath#coupling}），
 * 堆叠数 = 偏好周期（调谐旋钮），形态 = 线圈分组（{@code configureCoils}）。</p>
 *
 * <p>调度：{@code getPriority()} = 3，必须晚于红石层（priority 2）——
 * 电力采样依赖红石已算完的 edgeGrid / prevEdgeGrid 双缓冲。</p>
 */
public class LivingWaxedCopperFunction implements LivingItemFunction, HasContainerData, HasDirection {

    public static final String ID = "living_waxed_copper";

    /** 感应耦合最大转发层数（层 0 = 直连辐射；耦合深度越大衰减越重） */
    private static final int MAX_COUPLING_LAYERS = 3;

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
        int width = ctx.getWidth();
        long now = powerData.currentTick();

        // ── 收集发电机（铜灯跳过）+ 形状配置 ──
        Map<Integer, GeneratorState> active = new HashMap<>();
        Map<Integer, Integer> oxidation = new HashMap<>();
        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || isWaxedBulb(stack.getItem())) continue;   // 铜灯 = 电池（阶段四）

            int slot = entry.slotIndex();
            if (slot < 0 || slot >= size) continue;
            GeneratorState gen = powerData.getOrCreateGenerator(slot);
            gen.setPreferredPeriodFromStack(stack.getCount());
            configureCoils(gen, stack);
            active.put(slot, gen);
            oxidation.put(slot, getOxidationLevel(stack.getItem()));
        }
        if (active.isEmpty()) {
            powerData.endTick(0);
            return;
        }

        // ── Pass 1：直连采样（edgeGrid 逐方向 → 直连路） ──
        int[] edges = new int[ContainerRedstoneData.EDGE_COUNT];
        double[] directValue = new double[size];
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            GeneratorState gen = e.getValue();
            redstone.getEdgeValues(slot, edges);
            double sum = 0;
            for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                int ch = gen.dirChannel(dir);
                if (ch < 0) continue;
                ChannelState channel = gen.channel(ch);
                int pathIdx = gen.dirDirectPath(dir);
                int delta = channel.onPathValue(pathIdx, now, edges[dir]);
                if (delta != 0) {
                    credit(powerData, channel, pathIdx, gen.preferredPeriod(), delta);
                }
                sum += channel.path(pathIdx).lastValue();
            }
            directValue[slot] = sum;
        }

        // ── Pass 2：感应耦合（分层辐射 + 不回传 + 加权守恒） ──
        couple(active, oxidation, directValue, size, width, now, powerData);

        // ── 发电直存（§3.6 v17.5）：本 tick 发电量按剩余容量比例分配入铜灯堆 ──
        // 无铜灯 → 电凭空消失（显性浪费）。铜灯是唯一储存，容器只是铜灯的架子。
        long generatedRe = powerData.drainGeneratedRe();
        if (generatedRe > 0 && distributeToBulbs(generatedRe, entries)
                && ctx instanceof com.qiqi.li.living.container.SimpleContainerContext simpleCtx) {
            // 铜灯电量变更 → 标记容器数据已改（否则不落盘存档）
            for (net.minecraft.world.level.block.entity.BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                be.setChanged();
            }
        }

        powerData.endTick(generatedRe);
    }

    /**
     * 发电直存（§3.6 v17.5）：本 tick 发电量按「剩余容量比例」分配入各铜灯堆
     * （充电不限率，每盏 q += share/count 向下取整，零头保守丢弃）；
     * 无铜灯 → 电凭空消失（显性浪费）；铜灯全满 → 弃（电池已满）。
     * 电全部住在铜灯 DataComponent 里，随物品走、随 NBT 持久化。
     *
     * @param generatedRe 本 tick 发电量（RE，来自 {@code drainGeneratedRe()}）
     * @return true 表示有铜灯实际充入了电量（需 setChanged 落盘）
     */
    static boolean distributeToBulbs(long generatedRe, List<SlotEntry> entries) {
        long mfe = Math.round(generatedRe * PowerMath.RE_TO_FE * 1000.0);
        if (mfe <= 0) {
            return false;
        }

        record BulbRef(ItemStack stack, int count, long remaining) {}
        List<BulbRef> bulbs = new ArrayList<>();
        long totalRemaining = 0;
        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || !isWaxedBulb(stack.getItem())) continue;
            long rem = LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount())
                - LivingItemManager.getWaxedBulbData(stack).totalChargeMilliFe(stack.getCount());
            if (rem <= 0) continue;   // 该堆已满
            bulbs.add(new BulbRef(stack, stack.getCount(), rem));
            totalRemaining += rem;
        }

        if (bulbs.isEmpty()) {
            return false;   // 无存储 → 电凭空消失（显性浪费）
        }

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
        return distributed > 0;   // 零头与满溢 → 弃（电池已满/损耗）
    }

    /** 单路跳变 → 合因子 → RE 入账 */
    private static void credit(ContainerPowerData powerData, ChannelState channel, int pathIdx,
                               int preferredPeriod, int delta) {
        double factor = channel.factorFor(pathIdx, preferredPeriod);
        long re = PowerMath.eventEnergyRe(Math.abs(delta), factor,
            channel.path(pathIdx).periodTicks());
        powerData.onEventEnergy(re);
    }

    /**
     * 感应耦合（§3.5）：相邻发电机按管径 c 加权分配直连振荡，
     * 分层转发（≤ {@value MAX_COUPLING_LAYERS} 层，不回传来源方向杜绝自激回环），
     * 分叉守恒：share = c_T / Σc_下游。
     *
     * <p>v1 为同频转发；分频（周期 ×2）转发待实现（见 living-power-tech.md 已知限制）。</p>
     */
    private static void couple(Map<Integer, GeneratorState> active, Map<Integer, Integer> oxidation,
                               double[] directValue, int size, int width, long now,
                               ContainerPowerData powerData) {
        Map<Integer, double[]> receivedDir = new HashMap<>();      // 节点 → 各方向收到的感应值
        Map<Integer, Double> relay = new HashMap<>();              // 本层待辐射值
        Map<Integer, Set<Integer>> sources = new HashMap<>();      // 节点的感应来源（禁止回传）
        Set<Integer> enqueued = new HashSet<>();
        Set<Integer> relayed = new HashSet<>();                    // 已辐射（每节点最多辐射两次：root + 中继）

        // 层 0：直连辐射源（直连复合值非零的发电机）
        for (var e : active.entrySet()) {
            if (directValue[e.getKey()] != 0) {
                relay.put(e.getKey(), directValue[e.getKey()]);
                enqueued.add(e.getKey());
            }
        }

        for (int layer = 0; layer <= MAX_COUPLING_LAYERS && !relay.isEmpty(); layer++) {
            Map<Integer, Double> layerReceived = new HashMap<>();
            Map<Integer, Set<Integer>> layerSources = new HashMap<>();
            Set<Integer> nextEnqueue = new HashSet<>();

            for (var e : relay.entrySet()) {
                int s = e.getKey();
                if (relayed.contains(s)) continue;
                relayed.add(s);
                double relayV = e.getValue();
                if (relayV == 0) continue;

                // 候选下游：相邻发电机，排除来源方向（不回传）
                List<Integer> targets = new ArrayList<>();
                List<Integer> targetDirs = new ArrayList<>();
                double cSum = 0;
                for (int dir = 0; dir < ContainerRedstoneData.EDGE_COUNT; dir++) {
                    int tSlot = ContainerRedstoneData.resolveSlot(s, dir, size, width);
                    if (tSlot < 0 || !active.containsKey(tSlot)) continue;
                    Set<Integer> from = sources.get(s);
                    if (from != null && from.contains(tSlot)) continue;
                    targets.add(tSlot);
                    targetDirs.add(dir);
                    cSum += PowerMath.coupling(oxidation.get(tSlot));
                }
                if (targets.isEmpty() || cSum <= 0) continue;

                for (int i = 0; i < targets.size(); i++) {
                    int tSlot = targets.get(i);
                    double v = relayV * PowerMath.coupling(oxidation.get(tSlot)) / cSum;
                    int tDir = opposite(targetDirs.get(i));
                    receivedDir.computeIfAbsent(tSlot, k -> new double[4])[tDir] += v;
                    layerReceived.merge(tSlot, v, Double::sum);
                    layerSources.computeIfAbsent(tSlot, k -> new HashSet<>()).add(s);
                }
            }

            // 层末：新接收节点入队中继（值 = 自身直连 + 首次接收量；同层多源先合并）
            for (var e : layerReceived.entrySet()) {
                int t = e.getKey();
                if (enqueued.contains(t)) continue;   // 已入过队（root 或中继）→ 不重复中继
                enqueued.add(t);
                nextEnqueue.add(t);
                relay.put(t, directValue[t] + e.getValue());
                sources.put(t, layerSources.get(t));
            }
            // 已入队但未辐射的节点继续留在队列（relay map 即队列）
            for (Integer t : List.copyOf(relay.keySet())) {
                if (!relayed.contains(t) && !nextEnqueue.contains(t) && enqueued.contains(t)) {
                    nextEnqueue.add(t);
                }
            }
            relay.keySet().retainAll(nextEnqueue);
            // 补齐 sources 引用（中继节点用自己的来源集）
            for (Integer t : relay.keySet()) {
                sources.computeIfAbsent(t, k -> new HashSet<>());
            }
        }

        // ── 结算：虚拟路每 tick 都要喂（0 也是有效电平，否则波形停格测不到跳变）──
        // 只喂有发电机邻居的方向
        for (var e : active.entrySet()) {
            int slot = e.getKey();
            GeneratorState gen = e.getValue();
            double[] dirs = receivedDir.get(slot);
            for (int d = 0; d < ContainerRedstoneData.EDGE_COUNT; d++) {
                int neighbor = ContainerRedstoneData.resolveSlot(slot, d, size, width);
                if (neighbor < 0 || !active.containsKey(neighbor)) continue;
                int ch = gen.dirChannel(d);
                if (ch < 0) continue;
                ChannelState channel = gen.channel(ch);
                int idx = gen.dirVirtualPath(d);
                int value = dirs == null ? 0 : (int) Math.round(dirs[d]);
                int delta = channel.onPathValue(idx, now, value);
                if (delta != 0) {
                    credit(powerData, channel, idx, gen.preferredPeriod(), delta);
                }
            }
        }
    }

    /** 反方向：UP↔DOWN、LEFT↔RIGHT（常量按 0,1,2,3 相邻排列，异或 1 即反向） */
    private static int opposite(int dir) {
        return dir ^ 1;
    }

    // ── 线圈分组（§3.4 感应拓扑）──

    /** 形状决定通道划分；配置指纹不变时不重建（保护波形状态） */
    static void configureCoils(GeneratorState gen, ItemStack stack) {
        Item item = stack.getItem();
        if (isWaxedChiseled(item)) {
            gen.configureCoilsIfChanged(20_000, new int[][]{
                {ContainerRedstoneData.EDGE_UP, ContainerRedstoneData.EDGE_DOWN},       // V 线圈
                {ContainerRedstoneData.EDGE_LEFT, ContainerRedstoneData.EDGE_RIGHT}});  // H 线圈
        } else if (isWaxedCut(item)) {
            int dir = dirIndex(LivingItemManager.getWaxedCutData(stack).senseDir());
            gen.configureCoilsIfChanged(30_000 + dir, new int[][]{{dir}});
        } else {   // 铜块 / 格栅（格栅频率过滤待定，v1 同全向）
            gen.configureCoilsIfChanged(10_000, new int[][]{
                {ContainerRedstoneData.EDGE_UP, ContainerRedstoneData.EDGE_DOWN,
                 ContainerRedstoneData.EDGE_LEFT, ContainerRedstoneData.EDGE_RIGHT}});
        }
    }

    /** Pos2D → 边方向索引（非四正方向回退 UP） */
    static int dirIndex(Pos2D dir) {
        if (Pos2D.DOWN.equals(dir)) return ContainerRedstoneData.EDGE_DOWN;
        if (Pos2D.LEFT.equals(dir)) return ContainerRedstoneData.EDGE_LEFT;
        if (Pos2D.RIGHT.equals(dir)) return ContainerRedstoneData.EDGE_RIGHT;
        return ContainerRedstoneData.EDGE_UP;
    }

    // ── Tooltip ──

    @Override
    public void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag,
                             ItemStack stack) {
        var item = stack.getItem();
        int oxidation = getOxidationLevel(item);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.waxed_copper.title")
            .withStyle(ChatFormatting.GOLD));
        tooltipAdder.accept(Component.literal("  ")
            .append(Component.translatable("tooltip.livingitem.waxed_copper.coupling"))
            .append(Component.literal(": " + String.format("%.2f", PowerMath.coupling(oxidation))))
            .withStyle(ChatFormatting.GRAY));
        if (stack.getCount() >= 2) {
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.preferred_period"))
                .append(Component.literal(": " + stack.getCount() + " tick"))
                .withStyle(ChatFormatting.AQUA));
        } else {
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.broadband"))
                .withStyle(ChatFormatting.DARK_AQUA));
        }
        if (isWaxedBulb(item)) {
            LivingWaxedBulbData data = LivingItemManager.getWaxedBulbData(stack);
            long q = data.chargeMilliFe();
            long cap = LivingWaxedBulbData.totalCapacityMilliFe(stack.getCount());
            boolean full = q >= cap;
            tooltipAdder.accept(Component.literal("  ")
                .append(Component.translatable("tooltip.livingitem.waxed_copper.battery"))
                .append(Component.literal(": " + q / 1000 + " / " + cap / 1000 + " FE"
                    + (full ? "（已满）" : "")))
                .withStyle(q > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        }
    }

    // ── WASD 方向配置（涂蜡切制的感应方向）──

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
