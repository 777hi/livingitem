package com.qiqi.li.living.domain.water;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 容器级流体数据 —— 参照原版水流平地蔓延逻辑实现。
 *
 * 核心映射：
 * - ContainerFluidData = 一个区块的流体状态
 * - 槽位 = 方块位置
 * - FlowEntry(level=0, isSource=true) = 流体源方块
 * - FlowEntry(level=1~7, isSource=false) = 流动流体方块
 * - 无 FlowEntry = 空气
 * - 活物品 = 阻挡水流的方块
 * - 非活物品 = 水中的实体（不阻挡水流，被水流推动）
 *
 * <p><b>流体类型维度（1b-1，2026-10-03）</b>：每个 {@link FlowEntry} 带 {@link FluidType} ——
 * 用<b>单张 map 带类型</b>（一槽只装一种流体，类比一个方块位置），不是「每流体一张 map」。
 * BFS 只在<b>同种流体</b>内扩散（已占用的格子不被别的流体覆盖）。</p>
 * <p>⚠️ <b>行为分档（1b-2）</b>：扩散由 {@link FluidFlowBehaviors} 查<b>每流体行为</b> ——
 * 静止流体只做源不扩散，会流动的按各自 {@code maxLevel}（水 7）。水行为仍<b>零变化</b>
 * （由 {@code ContainerFluidDataTest} 钉住）。「桶内容判定 / 跨流体交互」仍归后续（流体侧）。</p>
 *
 * 与原版一致的行为：
 * - 水源向4方向蔓延，level 递增，最远7格
 * - 每个 tick 通过 BFS 从所有水源重算流动状态
 * - 水源移除后，不可达的流动立即消失（简单实现）
 * - 多水源时，每个槽位取最近水源的 level
 * - 流动水记录 fromSlot（BFS 父节点），物品沿水流方向推动
 */
public class ContainerFluidData {

    public static final ContainerFluidData EMPTY = new ContainerFluidData() {
        @Override
        public void registerGeneratedSource(int slot, FluidType fluid) { }
        @Override
        public void removeGeneratedSource(int slot) { }
        @Override
        public boolean hasGeneratedSources() { return false; }
        @Override
        public void setLastTickTime(long time) { }
        @Override
        public void tick(ContainerContext ctx) { }
    };

    public static final int SOURCE_LEVEL = 0;
    public static final int MAX_FLOW_LEVEL = 7;

    public static class FlowEntry {
        int level;
        boolean isSource;
        int fromSlot;
        /** 该格流体类型（1b-1：单张 map 带类型 —— 一个槽位只装一种流体，类比一个方块位置）。 */
        final FluidType fluid;

        public FlowEntry(int level, boolean isSource, int fromSlot, FluidType fluid) {
            this.level = level;
            this.isSource = isSource;
            this.fromSlot = fromSlot;
            this.fluid = fluid;
        }

        public int level() { return level; }
        public boolean isSource() { return isSource; }
        public int fromSlot() { return fromSlot; }
        public FluidType fluid() { return fluid; }
    }

    private final Map<Integer, FlowEntry> flows = new LinkedHashMap<>();
    /**
     * 派生源（活水源，2026-10-03）—— <b>唯一的源形态</b>：槽位 → 源流体类型。
     *
     * <p>由倒水 / 晋升创建，<b>不随水桶离开消失</b>（原版语义：源是真实方块）。
     * 每 tick 在 {@link #recalculate} 播种阶段并入 BFS；生命周期：
     * 倒入/晋升而生，挤没（任何活物品进入该格）/汲走而死，容器销毁随 attachment 湮灭。</p>
     *
     * <p>⚠️ 必须记住流体类型 —— 否则重播种时岩浆源会退化成水。</p>
     * <p>⚠️ 桶源已退役（idea.md §〇.5）—— 旧「活水桶在场即源」路径连同
     * {@code registerSource}/{@code isLivingBucketOf} 一并删除。</p>
     */
    private final Map<Integer, FluidType> generatedSources = new LinkedHashMap<>();
    private long lastTickTime;
    private int tickCounter;

    public Map<Integer, FlowEntry> getFlows() {
        return flows;
    }

    public boolean isEmpty() {
        // ⚠️ 必须计入派生源：纯源容器（flows 尚空、仅 generatedSources 非空）不是空数据 ——
        // 否则驱动的 !isEmpty() 门会把它们永远挡在 tick 之外，BFS 无从启动、落盘也会漏。
        return flows.isEmpty() && generatedSources.isEmpty();
    }

    public long getLastTickTime() {
        return lastTickTime;
    }

    public void setLastTickTime(long time) {
        this.lastTickTime = time;
    }

    /**
     * 【查】指定槽位是否是流体源（供汲 / 倒处理器与渲染端查询）。
     *
     * <p>源是<b>容器级资产</b>，与「槽位里有没有物品」无关。</p>
     */
    public boolean isSource(int slot) {
        FlowEntry e = flows.get(slot);
        return e != null && e.isSource;
    }

    /**
     * 【查】指定槽位的源流体类型；非源返回 {@code null}。
     *
     * <p>供「汲水」判定该格是不是可汲的源、以及「倒水」判定目标格是否已有（异种）源。</p>
     */
    public FluidType sourceFluid(int slot) {
        FlowEntry e = flows.get(slot);
        return (e != null && e.isSource) ? e.fluid : null;
    }

    /** 【查】本容器当前是否存在任何流体源（廉价，不扫描槽位）。 */
    public boolean hasAnySource() {
        for (FlowEntry e : flows.values()) {
            if (e.isSource) return true;
        }
        return false;
    }

    // ── 派生源（活水源）生命周期 API ─────────────────────────

    /**
     * 【改】创建 / 覆盖一个派生源（倒水处理器用）。
     *
     * <p>同槽已有（异种）派生源 ⇒ 覆盖。⚠️ 接岩浆后，倒水处理器须先问跨流体交互
     * （原版往岩浆源倒水是石头/黑曜石，不是覆盖）。</p>
     */
    public void registerGeneratedSource(int slot, FluidType fluid) {
        if (fluid == null) return;
        generatedSources.put(slot, fluid);
    }

    /** 【改】移除派生源（汲走处理器用）。非派生源的槽位无效果。 */
    public void removeGeneratedSource(int slot) {
        generatedSources.remove(slot);
    }

    /** 【查】该槽位是否为派生源（区别于桶源）。 */
    public boolean isGeneratedSource(int slot) {
        return generatedSources.containsKey(slot);
    }

    /** 【查】本容器是否存在任何派生源（廉价）。 */
    public boolean hasGeneratedSources() {
        return !generatedSources.isEmpty();
    }

    /** 【查】派生源只读视图（供落盘 CODEC / 导出用；fluid 侧定义，框架接序列化）。 */
    public Map<Integer, FluidType> getGeneratedSources() {
        return java.util.Collections.unmodifiableMap(generatedSources);
    }

    // ── 落盘 CODEC（1b-2⑧，框架）────────────────────────────
    // 只序列化「派生源」map（槽位 → 流体类型）；流动表每 tick 由 BFS 重算，落盘无正确性价值。
    // 桶源**不落盘** —— 桶在场时每 tick 由桶重新注册，落盘无意义且会造出幽灵源。

    /** 序列化形态：一条派生源（槽位 + 流体类型注册 id）。 */
    private record SourceEntry(int slot, String fluidId) {
        static final Codec<SourceEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("slot").forGetter(SourceEntry::slot),
            Codec.STRING.fieldOf("fluid").forGetter(SourceEntry::fluidId)
        ).apply(i, SourceEntry::new));
    }

    /** 落盘 CODEC：只存 {@link #generatedSources}。重建后流动表为空，下一 tick 由 BFS 重算。 */
    public static final Codec<ContainerFluidData> CODEC =
        SourceEntry.CODEC.listOf().xmap(ContainerFluidData::fromSources, ContainerFluidData::toSources);

    /**
     * 按容器键索引的落盘 CODEC（B.5 第三项，2026-10-04）—— 玩家背包 / 末影箱用。
     *
     * <p>一个玩家有<b>两个</b>容器（背包 {@code player_<uuid>} + 末影箱 {@code player_<uuid>_ender_chest}），
     * 二者都<b>无 BE</b> 可挂 {@code .serialize} ⇒ 落到 <b>Player attachment</b>（单值 holder），
     * 用本 CODEC 存「容器键 → 流体数据」的映射（值仍是 {@link #CODEC}：只存派生源）。</p>
     */
    public static final Codec<Map<String, ContainerFluidData>> KEYED_CODEC =
        Codec.unboundedMap(Codec.STRING, CODEC);

    private static ContainerFluidData fromSources(List<SourceEntry> entries) {
        ContainerFluidData data = new ContainerFluidData();
        for (SourceEntry e : entries) {
            FluidType fluid = resolveFluidType(e.fluidId());
            if (fluid != null) data.generatedSources.put(e.slot(), fluid);
        }
        return data;
    }

    private static List<SourceEntry> toSources(ContainerFluidData data) {
        List<SourceEntry> out = new ArrayList<>();
        for (var en : data.generatedSources.entrySet()) {
            ResourceLocation rl = NeoForgeRegistries.FLUID_TYPES.getKey(en.getValue());
            if (rl != null) out.add(new SourceEntry(en.getKey(), rl.toString()));
        }
        return out;
    }

    /** 流体类型注册 id → {@link FluidType}；未知 id 返回 {@code null}（安全丢弃）。 */
    private static FluidType resolveFluidType(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : NeoForgeRegistries.FLUID_TYPES.getOptional(rl).orElse(null);
    }

    public void tick(ContainerContext ctx) {
        int containerSize = ctx.getSize();
        int width = ctx.getWidth();
        if (containerSize <= 0 || width <= 0) return;

        tickCounter++;

        // 目标层：拓扑每 tick 全量重算（播种挤没 + BFS）—— 移除/挤没/拓扑即时
        Map<Integer, FlowEntry> targets = computeTargets(containerSize, width, ctx);

        // 实际层：flows 向目标推进（生长按流体节拍渐进，移除/源即时）
        pruneActual(targets);
        ensureSourceCells(targets);
        Set<FluidType> advanced = advanceGrowth(targets, containerSize, width, ctx);

        // ── 生长类逻辑：一律走**该流体自己的节拍**（2026-10-06 统一时钟）──
        // 晋升：实际层 + 该流体推进拍（升格当拍改写为源）
        promoteInActualLayer(advanced, containerSize, width);
        // 推动：与该流体的蔓延同拍同频，排在蔓延之后（物品跟着刚长出的前沿走）
        pushItems(ctx, containerSize, width, advanced);

        // ── 反应类逻辑：当拍生效（外部投放 / 异种流体接触）──
        // 机制三：源格 = 转化台（水的表条目转化；岩浆不转化）
        transformSourceItems(ctx);
        // 机制五：熔岩格四邻有水 ⇒ 产物凝固落格（刷石机 / 黑曜石）
        reactFrontiers(ctx);
        // 机制一：熔岩格（含源格）非活物品焚毁/源诞生
        incinerateFlowItems(ctx);
    }

    /** 每流体推进节拍记忆（tickCounter）；跨存档不保留（重进流体重新生长，已拍板）。 */
    private final Map<FluidType, Integer> lastAdvance = new HashMap<>();

    /**
     * 机制一·焚毁（2026-10-06 定稿）：熔岩格（<b>含源格</b>）上的非活物品按行为判定处置——
     * {@code BURN} 销毁、{@code SPAWN_SOURCE} 消耗满组石头系物品并（若该格还不是源）诞生活熔岩源。
     *
     * <p>⚠️ <b>源格同样焚毁</b>（2026-10-06 口径更正）：源格是<b>水</b>的转化台（机制三，
     * 产物要存活到被抽走）—— 但<b>岩浆不转化</b>，岩浆源格上的物品只有焚毁一条路；
     * 满组石头系落在已有岩浆源上 = 只消耗石头（源已存在，不重复诞生）。
     * 防火物品（原版 {@code fireResistant}，下界合金系）存活共存。
     * 活物品不进入熔岩格（阻挡蔓延 ⇒ 源格会先被挤没），不在本判定内。</p>
     *
     * <p>{@code SPAWN_SOURCE} 诞生的源<b>当拍即可用</b>（对齐「源即时」不变量：
     * 直接改写实际层流表，不等下一拍 {@link #ensureSourceCells}）。</p>
     */
    private void incinerateFlowItems(ContainerContext ctx) {
        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fe.fluid());
            if (!behavior.canFlow()) continue;   // 静止流体（未注册等）不焚毁
            int slot = e.getKey();
            ItemStack item = ctx.getItem(slot);
            if (item.isEmpty() || LivingItemManager.isLivingItem(item)) continue;
            switch (behavior.incinerateResult(item)) {
                case BURN -> {
                    ctx.setItem(slot, ItemStack.EMPTY);
                    ctx.syncSlotToClients(slot, ItemStack.EMPTY);   // setItem 不管 GUI 刷新，须显式同步
                }
                case SPAWN_SOURCE -> {
                    ctx.setItem(slot, ItemStack.EMPTY);
                    ctx.syncSlotToClients(slot, ItemStack.EMPTY);   // 同上
                    registerGeneratedSource(slot, fe.fluid());
                    // 源即时：实际层当拍改写为源（渲染/转化/汲倒查询立即正确，不等下一拍）
                    flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, fe.fluid()));
                }
                default -> { }
            }
        }
    }

    /**
     * 刷石机·凝固反应（2026-10-06 定稿）：熔岩格四邻有水 ⇒ 该格熔岩退去、产物物品凝固落格
     * （原地累加：格上已是同种产物则 {@code +1}）。⚠️ <b>产物格不是墙</b>（2026-10-06 口径更正）——
     * 岩浆下一轮会重新流入同一格并再次反应 ⇒ <b>圆石数量持续增长</b>（刷石机自动产出）；
     * 产物满组（64）后不再反应，岩浆停在格内。
     * ⚠️ 只处理熔岩格——水格不反应（对齐原版：岩浆把自己转化掉，水不动）。
     */
    private void reactFrontiers(ContainerContext ctx) {
        record Reaction(int slot, ItemStack product, boolean wasSource) {}
        List<Reaction> reactions = new ArrayList<>();
        int[] buf = new int[4];   // 2026-10-07：本相位复用缓冲（每容器每拍 1 次分配，而非每格 1 次）

        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            FluidType fluid = fe.fluid();
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fluid);
            int slot = e.getKey();
            // 异种流体邻居 ⇒ 问本格行为「前沿反应」；null = 共存不反应
            // ⚠️ 源格走 frontierSourceReaction（黑曜石），流动格走 frontierReaction（圆石）
            ItemStack product = null;
            int nbCount = ContainerContext.fillNeighbors(buf, slot, ctx.getSize(), ctx.getWidth());
            for (int i = 0; i < nbCount; i++) {
                FlowEntry neighbor = flows.get(buf[i]);
                if (neighbor == null || neighbor.fluid() == fluid) continue;
                ItemStack candidate = frontierProduct(behavior, fe.isSource(), neighbor.fluid());
                if (candidate != null) {
                    product = candidate;
                    break;
                }
            }
            if (product == null) continue;

            reactions.add(new Reaction(slot, product, fe.isSource()));
        }

        for (Reaction r : reactions) {
            ItemStack existing = ctx.getItem(r.slot());
            if (existing.isEmpty()) {
                ctx.setItem(r.slot(), r.product());
                ctx.syncSlotToClients(r.slot(), r.product());   // setItem 不管 GUI 刷新，须显式同步
            } else if (ItemStack.isSameItemSameComponents(existing, r.product())
                    && existing.getCount() < existing.getMaxStackSize()) {
                existing.grow(1);
                ctx.setItem(r.slot(), existing);
                ctx.syncSlotToClients(r.slot(), existing);      // 同上（数量累加也要刷）
            } else {
                continue;   // 产物放不下（满组 / 异种物品占据）⇒ 本拍不反应，岩浆留在格内
            }
            if (r.wasSource()) {
                removeGeneratedSource(r.slot());   // 岩浆源被反应湮灭（v1 给圆石；原版此处是黑曜石，未做）
            }
            flows.remove(r.slot());
            // 产物格不是墙：下一轮 BFS 照常把岩浆送回来 ⇒ 再反应 ⇒ 产物累加
        }
    }

    /**
     * 前沿产物查询（2026-10-06 黑曜石循环）：<b>源格</b>问 {@code frontierSourceReaction}、
     * <b>流动格</b>问 {@code frontierReaction} —— 对齐原版 {@code shouldSpreadLiquid}
     * 的「本格是源 ⇒ 黑曜石，流动 ⇒ 圆石」，与「谁撞谁」无关。
     */
    private static ItemStack frontierProduct(FluidFlowBehavior behavior, boolean selfIsSource,
                                             FluidType neighborFluid) {
        return selfIsSource ? behavior.frontierSourceReaction(neighborFluid)
                            : behavior.frontierReaction(neighborFluid);
    }

    /**
     * 转化（1b-2 接缝）：每流体拍在<b>源格</b>问行为「格上物品是否转化」。
     *
     * <p>默认行为 {@link FluidFlowBehavior#transformItem} 返回 {@code null} ⇒ 什么都不做。
     * ⚠️ 转化产物若是活物品，下一拍会被「挤没」销毁 —— 流体侧定义转化表时须留意。</p>
     */
    private void transformSourceItems(ContainerContext ctx) {
        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            if (!fe.isSource) continue;
            int slot = e.getKey();
            ItemStack item = ctx.getItem(slot);
            if (item.isEmpty()) continue;
            ItemStack transformed = FluidFlowBehaviors.of(fe.fluid).transformItem(item);
            if (transformed != null) {
                // ⚠️ 转化是**催化剂语义**：永不消耗源（2026-10-06 定稿 —— 非活化物品
                // 不得消耗活化资产；原「空桶→水桶消耗源」条目已删除）。
                // 另注：setItem 的实现是「先 extractItem 抽干槽位、再 insertItem 插入新栈」，
                // 调用方持有的旧栈活引用会被抽干成空栈 —— 任何「setItem 后再读旧引用」的写法都会中招。
                ctx.setItem(slot, transformed);
                ctx.syncSlotToClients(slot, ctx.getItem(slot));   // setItem 不管 GUI 刷新，须显式同步
            }
        }
    }

    /**
     * BFS 从所有水源重算流动状态。
     *
     * 类比原版：每个 tick 重新计算所有流动水的 level，
     * 确保水源增减、活物品放置/移除后流动状态立即更新。
     *
     * - 活物品阻挡水流（类比方块）
     * - 非活物品不阻挡水流（类比实体，水穿过）
     * - 每个槽位取最近水源的 level（多水源取最小值）
     */
    /**
     * 目标层计算：拓扑每 tick 全量重算 —— 播种（挤没判定）+ BFS，<b>纯函数</b>。
     * 返回<b>目标流表</b>（每个格子的流体最终应到哪），不直接写 {@link #flows} ——
     * 实际层（flows）由 {@link #advanceGrowth} 按流体节拍向目标推进。
     *
     * <p>⚠️ 2026-10-06：晋升收敛循环已**删除** —— 晋升改到实际层、随该流体节拍发生
     * （见 {@link #promoteInActualLayer}），升格后的水网扩张由逐拍生长自然承担，
     * 不再需要同拍重跑 BFS。</p>
     */
    private Map<Integer, FlowEntry> computeTargets(int containerSize, int width, ContainerContext ctx) {
        return spread(containerSize, width, ctx);
    }

    // ── 实际层三相位（时序化，2026-10-05）────────────────────

    /**
     * 相位一·修剪：实际层中<b>目标已消失</b>或<b>流体已变</b>的格 ⇒ 当拍删除（移除/挤没/异种覆盖即时，
     * 不渐进干涸）。源格恒有目标（播种保证），不受影响。
     */
    private void pruneActual(Map<Integer, FlowEntry> targets) {
        flows.keySet().removeIf(slot -> {
            FlowEntry target = targets.get(slot);
            FlowEntry actual = flows.get(slot);
            return target == null || target.fluid() != actual.fluid();
        });
    }

    /**
     * 相位二·源即时：目标层的源格在**实际层立即可用**（不受流体节拍限制）——
     * 倒水/晋升产生的源当拍生效（渲染/转化/汲倒查询立即正确）。异种覆盖在此同步流体。
     */
    private void ensureSourceCells(Map<Integer, FlowEntry> targets) {
        for (var e : targets.entrySet()) {
            FlowEntry target = e.getValue();
            if (!target.isSource()) continue;
            FlowEntry cur = flows.get(e.getKey());
            if (cur == null || !cur.isSource() || cur.fluid() != target.fluid()) {
                flows.put(e.getKey(), new FlowEntry(SOURCE_LEVEL, true, -1, target.fluid()));
            }
        }
    }

    /**
     * 相位三·生长：非源格按<b>流体节拍</b>向目标推进（元胞自动机，同步快照语义）。
     *
     * <p>单步：{@code 新实际 = min(目标, min同流体邻居实际 + 1)} ——
     * {@code min 邻居} = 最近供给源胜出（对齐原版 getNewLiquid 的 max(amount)−dropOff
     * 取最优供给），{@code min 目标} 兜住不越过 BFS 距离。
     * 无同流体实际邻居供给的格 = 前沿未到 ⇒ 不出现（保留已有实际不倒退）。</p>
     *
     * <p>节拍：{@code flowSpeed() < 0} = 派生自原版 {@code getTickDelay}
     * （水 5 / 岩浆 30，下界加速自动成立）；{@code 0} = 瞬时（实际直接取目标）；
     * {@code N} = 每 N tick 推进一格。静止流体（canFlow=false）无生长。</p>
     *
     * <p>⚠️ 返回<b>本拍真正推进过的流体集合</b>（2026-10-06 统一时钟）：晋升与物品推动
     * 只对本集合里的流体执行 ⇒ 二者与该流体的蔓延<b>同拍同频</b>。</p>
     *
     * @return 本拍推进过的流体（瞬时模式 = 全部会流动的目标流体）
     */
    private Set<FluidType> advanceGrowth(Map<Integer, FlowEntry> targets, int containerSize, int width,
                                         ContainerContext ctx) {
        Level level = ctx.getLevel();
        Set<FluidType> advanced = new LinkedHashSet<>();
        int[] buf = new int[4];   // 2026-10-07：本相位复用缓冲（每容器每拍 1 次分配，而非每格 1 次）
        // 按流体分组非源目标格
        Map<FluidType, List<Integer>> byFluid = new LinkedHashMap<>();
        for (var e : targets.entrySet()) {
            if (e.getValue().isSource()) continue;
            byFluid.computeIfAbsent(e.getValue().fluid(), k -> new ArrayList<>()).add(e.getKey());
        }

        for (var group : byFluid.entrySet()) {
            FluidType fluid = group.getKey();
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fluid);
            if (!behavior.canFlow()) continue;   // 静止流体：只做源，无生长

            int speed = behavior.flowSpeed();
            if (speed < 0) speed = vanillaTickDelay(fluid, level);
            if (speed <= 0) {
                // 瞬时模式：实际直接取目标（每拍都算推进过）
                for (int slot : group.getValue()) {
                    FlowEntry t = targets.get(slot);
                    flows.put(slot, new FlowEntry(t.level(), t.isSource(), t.fromSlot(), fluid));
                }
                advanced.add(fluid);
                continue;
            }

            Integer last = lastAdvance.get(fluid);
            if (last != null && tickCounter - last < speed) continue;   // 未到节拍
            lastAdvance.put(fluid, tickCounter);
            advanced.add(fluid);

            // 同步快照语义：全部新值基于推进前快照计算，随后统一应用
            List<Runnable> updates = new ArrayList<>();
            for (int slot : group.getValue()) {
                FlowEntry target = targets.get(slot);
                int bestFeeder = Integer.MAX_VALUE;
                int feederSlot = -1;
                int nbCount = ContainerContext.fillNeighbors(buf, slot, containerSize, width);
                for (int i = 0; i < nbCount; i++) {
                    FlowEntry neighbor = flows.get(buf[i]);
                    if (neighbor == null || neighbor.fluid() != fluid) continue;
                    if (neighbor.level() + 1 < bestFeeder) {
                        bestFeeder = neighbor.level() + 1;
                        feederSlot = buf[i];
                    }
                }
                if (bestFeeder == Integer.MAX_VALUE) continue;   // 前沿未到：不出现/不推进
                FlowEntry cur = flows.get(slot);
                int newLevel = Math.min(target.level(), bestFeeder);
                if (cur != null && cur.level() == newLevel && cur.fromSlot() == feederSlot) continue;
                final int lv = newLevel, fs = feederSlot;
                updates.add(() -> flows.put(slot, new FlowEntry(lv, false, fs, fluid)));
            }
            updates.forEach(Runnable::run);
        }
        return advanced;
    }

    /**
     * 晋升（2026-10-06 统一时钟：从目标层搬到<b>实际层</b> + 该流体节拍）。
     *
     * <p>判定输入 = <b>实际层</b>四邻中已是源的个数（原版 {@code getNewLiquid}：
     * 「本格有流体」+「水平相邻同流体源 ≥2」⇒ 升源）—— 不再在目标层做「预见式升源」。
     * 只对本拍<b>推进过</b>的流体执行（与蔓延同频）；升格<b>当拍</b>改写实际层为源（源即时）。</p>
     *
     * <p>候选必然是「实际存在的流动格」：活物品格在 {@link #pruneActual} 已被清除
     * （活物品阻挡 BFS ⇒ 目标层无该格），因此不会出现「格里有活物品却被升源」。</p>
     *
     * <p>⚠️ 再生时序由「下一 tick」变为「下一推进拍」（水 ≤5t / 瞬时模式 = 每拍）——
     * 用户 2026-10-06 确认接受：一致性优先，要高供水速率靠多源并行。</p>
     */
    private void promoteInActualLayer(Set<FluidType> advanced, int containerSize, int width) {
        int[] buf = new int[4];   // 2026-10-07：本相位复用缓冲（晋升会嵌套调用邻源计数，故单独持有）
        for (FluidType fluid : advanced) {
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fluid);
            List<Integer> candidates = new ArrayList<>();
            for (var e : flows.entrySet()) {
                FlowEntry fe = e.getValue();
                if (fe.isSource || fe.fluid() != fluid) continue;
                if (behavior.shouldPromote(e.getKey(),
                        countSourceNeighbors(flows, e.getKey(), containerSize, width, fluid, buf))) {
                    candidates.add(e.getKey());
                }
            }
            for (int slot : candidates) {
                generatedSources.put(slot, fluid);
                flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, fluid));   // 源即时
            }
        }
    }

    /** 原版每格刻延迟（水 5 / 岩浆 30、下界 10）。level 为 null（单元测试/无维度环境）时按主世界兜底。 */
    private static int vanillaTickDelay(FluidType fluid, @javax.annotation.Nullable Level level) {
        net.minecraft.world.level.material.Fluid f = representativeFluid(fluid);
        if (level != null && f != null) return f.getTickDelay(level);
        boolean lava = f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
        return lava ? 30 : 5;   // null 维度兜底（单元测试/无 Level 环境）按主世界节奏
    }

    /** FluidType → 代表 {@link net.minecraft.world.level.material.Fluid}（读原版参数用），缓存。 */
    private static final Map<FluidType, net.minecraft.world.level.material.Fluid> REPRESENTATIVE = new HashMap<>();

    /**
     * {@link FluidType} → 代表 {@link net.minecraft.world.level.material.Fluid}（优先 still 态）。
     *
     * <p>**对外能力用**（{@link ContainerFluidHandler} 造 {@code FluidStack} 需要 vanilla Fluid）；
     * 未注册的模组流体返回 {@code null}（调用方按 EMPTY 处理）。</p>
     */
    public static net.minecraft.world.level.material.Fluid representativeFluidOf(FluidType type) {
        return representativeFluid(type);
    }

    private static net.minecraft.world.level.material.Fluid representativeFluid(FluidType type) {        if (REPRESENTATIVE.containsKey(type)) return REPRESENTATIVE.get(type);
        net.minecraft.world.level.material.Fluid found = null;
        for (var f : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
            if (f.getFluidType() != type) continue;
            if (found == null || f.defaultFluidState().isSource()) found = f;
            if (found != null && found.defaultFluidState().isSource()) break;
        }
        REPRESENTATIVE.put(type, found);
        return found;
    }

    /**
     * 扩散的一格候选（按<b>到达时间</b>排序，2026-10-07：距离 ⇒ 到达时间）。
     *
     * @param arrival 从源算起的累计到达时间（tick）
     * @param slot    目标格
     * @param level   BFS 步数（= 流体 level）
     * @param fromSlot 上游供给格
     * @param fluid   该路径所属流体
     */
    private record SpreadNode(int arrival, int slot, int level, int fromSlot, FluidType fluid) {}

    /** 该流体每蔓延一格的<b>时间成本</b>（0 = 瞬时 ⇒ 立刻铺满领地）。 */
    private static int perCellCost(FluidFlowBehavior behavior, FluidType fluid, Level level) {
        int speed = behavior.flowSpeed();
        if (speed < 0) speed = vanillaTickDelay(fluid, level);
        return Math.max(0, speed);
    }

    /**
     * 播种（派生源）+ 扩散**一轮**，返回流动表（不改 {@link #flows}）。
     *
     * <p><b>抢占规则 = 到达时间</b>（2026-10-07 定档，方案见
     * {@code docs/tech/living-fluid-tech.md §3.1}）：每个流体的每格成本 = 它自己的节拍
     * （水 5 / 岩浆 30，下界 10；瞬时 0），多源 Dijkstra 按到达时间升序定型 ⇒
     * <b>分配与实际到达用同一个时钟</b>。此前按距离抢占 ⇒ 分配与到达脱节 ⇒ 出现「在水的流域内
     * 却永远进不去」的墙（岩浆按距离/注册顺序守住接触格，哪怕它到得更慢）。</p>
     *
     * <p>不变量：<b>源格永不被蔓延抢占</b>（只能被反应湮灭）；<b>实际层被异种占据的格不抢</b>
     * （上一批的「不驱逐」）；maxLevel / 活物品阻挡 / 挤没播种语义不变。</p>
     */
    private Map<Integer, FlowEntry> spread(int containerSize, int width, ContainerContext ctx) {
        Map<Integer, FlowEntry> newFlows = new LinkedHashMap<>();
        Map<Integer, Integer> bestArrival = new HashMap<>();
        int[] buf = new int[4];   // 2026-10-07：本相位复用缓冲（每容器每拍 1 次分配，而非每格 1 次）
        PriorityQueue<SpreadNode> queue = new PriorityQueue<>(
            Comparator.comparingInt(SpreadNode::arrival).thenComparingInt(SpreadNode::slot));

        // 播种：派生源（活水源）—— 无条件并入，不依赖任何物品在场。
        // 挤没判定在此进行：任何活物品进入派生源格 ⇒ 源被挤没（永久销毁，原版「放方块进水源」语义）。
        // 不关心物品怎么来的（手放 / 未来活活塞推 / 任何途径）—— 判定只看槽位内容。
        // 非活物品不挤没（原版实体可与水源共存），派生源与物品同格，供后续机制三转化。
        if (!generatedSources.isEmpty()) {
            var it = generatedSources.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                int slot = entry.getKey();
                if (slot < 0 || slot >= containerSize) {
                    it.remove();
                    continue;
                }
                if (LivingItemManager.isLivingItem(ctx.getItem(slot))) {
                    it.remove();
                    continue;
                }
                newFlows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, entry.getValue()));
                bestArrival.put(slot, 0);
                queue.add(new SpreadNode(0, slot, SOURCE_LEVEL, -1, entry.getValue()));
            }
        }

        while (!queue.isEmpty()) {
            SpreadNode node = queue.poll();
            // 已有更短到达时间的路径定型过 ⇒ 本条废弃（多源 Dijkstra 的松弛）
            if (node.arrival() > bestArrival.getOrDefault(node.slot(), Integer.MAX_VALUE)) continue;

            // 行为分档（1b-2）：静止流体不扩散；会流动的按各自的 level 上限（水 7 / 岩浆 3）
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(node.fluid());
            if (!behavior.canFlow() || node.level() >= behavior.maxLevel()) continue;
            int cost = perCellCost(behavior, node.fluid(), ctx.getLevel());

            int nbCount = ContainerContext.fillNeighbors(buf, node.slot(), containerSize, width);
            for (int k = 0; k < nbCount; k++) {
                int neighbor = buf[k];
                // 源格永不被蔓延抢占（源只能由反应湮灭 / 汲走 / 挤没）—— 否则快流体会把源圈走
                FlowEntry claimed = newFlows.get(neighbor);
                if (claimed != null && claimed.isSource()) continue;

                ItemStack item = ctx.getItem(neighbor);
                if (LivingItemManager.isLivingItem(item)) continue;

                // 异种流体已**实际**占据该格 ⇒ 不抢（2026-10-07 定档，docs/tech/living-fluid-tech.md §3.1）：
                // 原版语义是「接触面直接反应」（shouldSpreadLiquid 在岩浆格上凝固），熔岩前沿
                // 停在接触面、逐格凝固，**从不驱逐对方**。此前目标层按距离抢占 ⇒ pruneActual
                // 当拍删掉对方 ⇒ 接触前露出 1.5s 空档（水凭空消失一格，像 bug）。
                // ⚠️ 代价：目标层不再是纯 BFS 纯函数（要读实际层）——换来 pruneActual 只剩
                // 「目标消失」一个职责（异种驱逐的决策权挪进这里）。
                // ⚠️ 播种（generatedSources）**仍然覆盖**：倒桶是玩家显式行为，见 §5。
                FlowEntry occupied = flows.get(neighbor);
                if (occupied != null && occupied.fluid() != node.fluid()) continue;

                // 到达时间更早 ⇒ 抢占（含改判：先前被更慢流体占下的格会被松弛掉）
                int arrival = node.arrival() + cost;
                if (arrival >= bestArrival.getOrDefault(neighbor, Integer.MAX_VALUE)) continue;

                int newLevel = node.level() + 1;
                bestArrival.put(neighbor, arrival);
                newFlows.put(neighbor, new FlowEntry(newLevel, false, node.slot(), node.fluid()));
                queue.add(new SpreadNode(arrival, neighbor, newLevel, node.slot(), node.fluid()));
            }
        }

        return newFlows;
    }

    /**
     * 该格四邻中已是**同流体**源的个数（晋升判定的输入，对齐原版 `getNewLiquid`
     * 的「水平相邻同流体源 ≥2」）。
     *
     * <p>🔴 2026-10-07 收尾审查修复：此前<b>不判流体</b> ⇒ 岩浆源会被算进水的邻源数 ⇒
     * 一格流动水夹在两个岩浆源之间会<b>错误晋升成水源</b>。</p>
     */
    private static int countSourceNeighbors(Map<Integer, FlowEntry> flows, int slot, int containerSize,
                                            int width, FluidType fluid, int[] buf) {
        int count = 0;
        int nbCount = ContainerContext.fillNeighbors(buf, slot, containerSize, width);
        for (int i = 0; i < nbCount; i++) {
            FlowEntry fe = flows.get(buf[i]);
            if (fe != null && fe.isSource && fe.fluid() == fluid) count++;
        }
        return count;
    }

    /**
     * 沿水流方向推动物品。
     *
     * 构建 BFS 水流树的下游映射（fromSlot → 子节点列表），
     * 物品被推到其下游子节点，自然跟随水流的弯曲路径。
     * 按 level 升序处理，离水源近的先推，物品沿水流方向逐层向外移动。
     * 支持堆叠：目标槽位有同类物品时合并。
     *
     * 安全约束：
     * - 不推入水源槽位（活水桶所在）
     * - 不推入活物品槽位
     * - 不推入已有非空非堆叠物品的槽位
     * - 堆叠合并通过 ctx.setItem() 确保容器变更通知
     * - 使用 moved 标记避免同一物品被级联推动两次
     */
    private void pushItems(ContainerContext ctx, int containerSize, int width, Set<FluidType> advanced) {
        for (FluidType fluid : advanced) {
            pushFluidItems(ctx, containerSize, width, fluid);
        }
    }

    /**
     * 某一流体自己的流树推动（2026-10-06 统一时钟：<b>每流体节拍一次</b>，不再是全局 4t）。
     *
     * <p>只处理该流体的格：下游映射、level 升序遍历、{@code moved} 集合全部按流体切片，
     * 避免「A 流体的物品被 B 流体的树推动」。水 5t / 岩浆 30t（黏性对上）。</p>
     */
    private void pushFluidItems(ContainerContext ctx, int containerSize, int width, FluidType fluid) {
        Map<Integer, List<Integer>> downstream = new HashMap<>();
        for (var entry : flows.entrySet()) {
            if (entry.getValue().fluid() != fluid) continue;
            int slot = entry.getKey();
            FlowEntry fe = entry.getValue();
            if (fe.fromSlot >= 0) {
                downstream.computeIfAbsent(fe.fromSlot, k -> new ArrayList<>()).add(slot);
            }
        }

        List<Map.Entry<Integer, FlowEntry>> sorted = new ArrayList<>();
        for (var entry : flows.entrySet()) {
            if (entry.getValue().fluid() == fluid) sorted.add(entry);
        }
        sorted.sort((a, b) -> Integer.compare(a.getValue().level, b.getValue().level));

        Set<Integer> moved = new HashSet<>();

        for (var entry : sorted) {
            int slot = entry.getKey();
            FlowEntry fe = entry.getValue();
            if (fe.isSource) continue;
            if (moved.contains(slot)) continue;

            ItemStack item = ctx.getItem(slot);
            if (item.isEmpty() || LivingItemManager.isLivingItem(item)) continue;

            List<Integer> children = downstream.get(slot);
            if (children == null || children.isEmpty()) continue;

            for (int targetSlot : children) {
                if (targetSlot < 0 || targetSlot >= containerSize) continue;

                FlowEntry targetFlow = flows.get(targetSlot);
                if (targetFlow != null && targetFlow.isSource) continue;

                ItemStack targetItem = ctx.getItem(targetSlot);
                if (LivingItemManager.isLivingItem(targetItem)) continue;

                if (targetItem.isEmpty()) {
                    ctx.setItem(targetSlot, item.copy());
                    ctx.setItem(slot, ItemStack.EMPTY);
                    ctx.syncSlotToClients(targetSlot, ctx.getItem(targetSlot));
                    ctx.syncSlotToClients(slot, ItemStack.EMPTY);
                    moved.add(targetSlot);
                    break;
                } else if (ItemStack.isSameItemSameComponents(item, targetItem)
                           && targetItem.getCount() < targetItem.getMaxStackSize()) {
                    int space = targetItem.getMaxStackSize() - targetItem.getCount();
                    int toAdd = Math.min(item.getCount(), space);
                    ItemStack newTarget = targetItem.copy();
                    newTarget.grow(toAdd);
                    ctx.setItem(targetSlot, newTarget);
                    ctx.syncSlotToClients(targetSlot, newTarget);
                    if (item.getCount() == toAdd) {
                        ctx.setItem(slot, ItemStack.EMPTY);
                        ctx.syncSlotToClients(slot, ItemStack.EMPTY);
                        moved.add(targetSlot);
                        break;
                    } else {
                        ItemStack remaining = item.copy();
                        remaining.shrink(toAdd);
                        ctx.setItem(slot, remaining);
                        ctx.syncSlotToClients(slot, remaining);
                        moved.add(targetSlot);
                        break;
                    }
                }
            }
        }
    }

    // ⚠️ 2026-10-07 收尾审查：原 exportFlowData() 全仓库零调用 ⇒ 删除（不为假想需求留 API）。
    //    需要导出流动表时读 getFlows()（已返回只读视图）。
}