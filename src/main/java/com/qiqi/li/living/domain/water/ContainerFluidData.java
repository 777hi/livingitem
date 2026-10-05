package com.qiqi.li.living.domain.water;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public static final int FLOW_STEP_TICKS = 4;

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

        // 目标层：拓扑每 tick 全量重算（播种挤没 + BFS + 晋升收敛）—— 移除/挤没/拓扑即时
        Map<Integer, FlowEntry> targets = computeTargets(containerSize, width, ctx);

        // 实际层：flows 向目标推进（生长按流体节拍渐进，移除/源即时）
        unmarkMinedSolidCells(ctx);
        pruneActual(targets);
        ensureSourceCells(targets);
        advanceGrowth(targets, containerSize, width, ctx);

        transformSourceItems(ctx);   // 机制三：源格 = 转化台（表条目，源格不焚毁）
        incinerateFlowItems(ctx);    // 机制一：熔岩流动格非活物品焚毁/源诞生（石头系满组）
        reactFrontiers(ctx);         // 刷石机：熔岩格四邻有水 ⇒ 凝固产物 + 固墙


        if (tickCounter % FLOW_STEP_TICKS == 0) {
            pushItems(ctx, containerSize, width);
        }
    }

    /** 每流体推进节拍记忆（tickCounter）；跨存档不保留（重进流体重新生长，已拍板）。 */
    private final Map<FluidType, Integer> lastAdvance = new HashMap<>();

    /**
     * 凝固墙（2026-10-06）—— 前沿反应产物（圆石/黑曜石物品）所在的格：
     * 两种流体的 BFS 都不再进入（原版：反应产物是实体方块，阻挡两侧流体）。
     * 挖走产物 ⇒ 下一拍解封 ⇒ 重新涌入/反应（刷石机再生）。
     * 跨存档不保留：重载后产物重新凝固（视觉自愈，可接受）。
     */
    private final Map<Integer, ItemStack> solidified = new HashMap<>();

    /**
     * 机制一·焚毁（2026-10-06）：<b>流动</b>熔岩格上的非活物品按行为判定处置——
     * {@code BURN} 销毁、{@code SPAWN_SOURCE} 消耗满组石头系物品并在该格诞生活熔岩源（新配方）。
     *
     * <p>⚠️ 源格 = 转化台（机制三），不焚毁 —— 转化产物（熔岩桶等）要存活到被漏斗抽走。
     * 防火物品（原版 {@code fireResistant}，下界合金系）存活共存。
     * 活物品不进入熔岩格（阻挡蔓延），不在本判定内。</p>
     *
     * <p>{@code SPAWN_SOURCE} 诞生的源<b>当拍即可用</b>（对齐「源即时」不变量：
     * 直接改写实际层流表，不等下一拍 {@link #ensureSourceCells}）。</p>
     */
    private void incinerateFlowItems(ContainerContext ctx) {
        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            if (fe.isSource()) continue;   // 源格 = 转化台，不焚毁
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fe.fluid());
            if (!behavior.canFlow()) continue;   // 静止流体（未注册等）不焚毁
            int slot = e.getKey();
            ItemStack item = ctx.getItem(slot);
            if (item.isEmpty() || LivingItemManager.isLivingItem(item)) continue;
            switch (behavior.incinerateResult(item)) {
                case BURN -> ctx.setItem(slot, ItemStack.EMPTY);
                case SPAWN_SOURCE -> {
                    ctx.setItem(slot, ItemStack.EMPTY);
                    registerGeneratedSource(slot, fe.fluid());
                    // 源即时：实际层当拍改写为源（渲染/转化/汲倒查询立即正确，不等下一拍）
                    flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, fe.fluid()));
                }
                default -> { }
            }
        }
    }

    /**
     * 刷石机·凝固反应（2026-10-06）：熔岩格四邻有水 ⇒ 该格熔岩退去、产物物品凝固落格、
     * 该格固墙（两侧流体不再进入）。挖走产物 ⇒ 解封 ⇒ 重新涌入/反应（原版刷石机再生）。
     * ⚠️ 只处理熔岩格——水格不反应（对齐原版：岩浆把自己转化掉，水不动）。
     */
    private void reactFrontiers(ContainerContext ctx) {
        record Reaction(int slot, ItemStack product, boolean wasSource) {}
        List<Reaction> reactions = new ArrayList<>();

        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            FluidType fluid = fe.fluid();
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fluid);
            int slot = e.getKey();
            // 异种流体邻居 ⇒ 问本格行为「前沿反应」（活熔岩对水：圆石）；null = 共存不反应
            FluidType reactionNeighbor = null;
            for (int n : ContainerContext.getNeighbors(slot, ctx.getSize(), ctx.getWidth())) {
                FlowEntry neighbor = flows.get(n);
                if (neighbor == null || neighbor.fluid() == fluid) continue;
                if (behavior.frontierReaction(neighbor.fluid()) != null) {
                    reactionNeighbor = neighbor.fluid();
                    break;
                }
            }
            if (reactionNeighbor == null) continue;

            ItemStack product = behavior.frontierReaction(reactionNeighbor);
            reactions.add(new Reaction(slot, product, fe.isSource()));
        }

        for (Reaction r : reactions) {
            ItemStack existing = ctx.getItem(r.slot());
            if (existing.isEmpty()) {
                ctx.setItem(r.slot(), r.product());
            } else if (ItemStack.isSameItemSameComponents(existing, r.product())
                    && existing.getCount() < existing.getMaxStackSize()) {
                existing.grow(1);
                ctx.setItem(r.slot(), existing);
            } else {
                continue;   // 产物放不下（异种物品占据）⇒ 不反应不固墙
            }
            if (r.wasSource()) {
                removeGeneratedSource(r.slot());   // 岩浆源被反应湮灭（产物=黑曜石）
            }
            flows.remove(r.slot());
            solidified.put(r.slot(), r.product());
        }
    }

    /** 固墙解封检查：产物被挖走（槽位物品变化/清空）⇒ 解封，下一拍重新涌入。 */
    private void unmarkMinedSolidCells(ContainerContext ctx) {
        solidified.entrySet().removeIf(e -> {
            ItemStack item = ctx.getItem(e.getKey());
            return item.isEmpty() || !ItemStack.isSameItemSameComponents(item, e.getValue());
        });
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
                // 消耗判定必须在 setItem 之前 —— setItem 的实现是「先 extractItem 抽干槽位、
                // 再 insertItem 插入新栈」，item（活引用）会被抽干成空栈，事后判定恒 false
                // （2026-10-06 实测口径：空桶→水桶必须消耗源，否则一格水 = 无限水桶；
                //   三连源场景晋升会再生中间源 ⇒ 自动化水桶农场成立，无限性来自三连源而非免费转化）
                boolean consumeSource = FluidFlowBehaviors.of(fe.fluid).consumesSourceOnTransform(item);
                ctx.setItem(slot, transformed);
                if (consumeSource) {
                    removeGeneratedSource(slot);
                }
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
     * 目标层计算（时序化后改名）：拓扑每 tick 全量重算 —— 播种（挤没判定）+ BFS + 晋升收敛。
     * 返回<b>目标流表</b>（每个格子的流体最终应到哪），不直接写 {@link #flows} ——
     * 实际层（flows）由 {@link #advanceGrowth} 按流体节拍向目标推进。
     */
    private Map<Integer, FlowEntry> computeTargets(int containerSize, int width, ContainerContext ctx) {
        Map<Integer, FlowEntry> newFlows;
        // 晋升收敛循环（1b-2 接缝）：升格为源后水网会扩张 ⇒ 重跑 BFS，直到无新升格。
        // 默认行为不晋升（shouldPromote 恒 false）⇒ 恰好一轮，与旧行为一致。
        while (true) {
            newFlows = spread(containerSize, width, ctx);
            if (!tryPromote(newFlows, containerSize, width)) break;
        }
        return newFlows;
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
     */
    private void advanceGrowth(Map<Integer, FlowEntry> targets, int containerSize, int width,
                               ContainerContext ctx) {
        Level level = ctx.getLevel();
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
                // 瞬时模式：实际直接取目标
                for (int slot : group.getValue()) {
                    FlowEntry t = targets.get(slot);
                    flows.put(slot, new FlowEntry(t.level(), t.isSource(), t.fromSlot(), fluid));
                }
                continue;
            }

            Integer last = lastAdvance.get(fluid);
            if (last != null && tickCounter - last < speed) continue;   // 未到节拍
            lastAdvance.put(fluid, tickCounter);

            // 同步快照语义：全部新值基于推进前快照计算，随后统一应用
            List<Runnable> updates = new ArrayList<>();
            for (int slot : group.getValue()) {
                FlowEntry target = targets.get(slot);
                int bestFeeder = Integer.MAX_VALUE;
                int feederSlot = -1;
                for (int n : ContainerContext.getNeighbors(slot, containerSize, width)) {
                    FlowEntry neighbor = flows.get(n);
                    if (neighbor == null || neighbor.fluid() != fluid) continue;
                    if (neighbor.level() + 1 < bestFeeder) {
                        bestFeeder = neighbor.level() + 1;
                        feederSlot = n;
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

    private static net.minecraft.world.level.material.Fluid representativeFluid(FluidType type) {
        if (REPRESENTATIVE.containsKey(type)) return REPRESENTATIVE.get(type);
        net.minecraft.world.level.material.Fluid found = null;
        for (var f : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
            if (f.getFluidType() != type) continue;
            if (found == null || f.defaultFluidState().isSource()) found = f;
            if (found != null && found.defaultFluidState().isSource()) break;
        }
        REPRESENTATIVE.put(type, found);
        return found;
    }

    /** 播种（派生源）+ BFS 扩散**一轮**，返回流动表（不改 {@link #flows}）。 */
    private Map<Integer, FlowEntry> spread(int containerSize, int width, ContainerContext ctx) {
        Map<Integer, FlowEntry> newFlows = new LinkedHashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();

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
                queue.add(slot);
            }
        }

        while (!queue.isEmpty()) {
            int slot = queue.poll();
            FlowEntry fe = newFlows.get(slot);
            // 行为分档（1b-2）：静止流体不扩散；会流动的按各自的 level 上限（水 7 / 岩浆 3）
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fe.fluid);
            if (!behavior.canFlow() || fe.level >= behavior.maxLevel()) continue;

            int[] neighbors = ContainerContext.getNeighbors(slot, containerSize, width);
            for (int neighbor : neighbors) {
                // 已被占用（含被别的流体占用）⇒ 不覆盖 —— 流体不混色
                if (newFlows.containsKey(neighbor)) continue;

                ItemStack item = ctx.getItem(neighbor);
                if (LivingItemManager.isLivingItem(item)) continue;
                if (solidified.containsKey(neighbor)) continue;   // 凝固墙：产物所在格阻挡两侧流体

                int newLevel = fe.level + 1;
                newFlows.put(neighbor, new FlowEntry(newLevel, false, slot, fe.fluid));
                queue.add(neighbor);
            }
        }

        return newFlows;
    }

    /**
     * 晋升检查（1b-2 接缝）：问每个<b>流动格</b>的行为「是否升格为源」；有升格则写入
     * {@link #generatedSources}（永久资产）并返回 {@code true}（外层据此重跑 BFS）。
     *
     * <p>默认行为 {@link FluidFlowBehavior#shouldPromote} 恒 false ⇒ 永不晋升，与旧行为一致。</p>
     */
    private boolean tryPromote(Map<Integer, FlowEntry> newFlows, int containerSize, int width) {
        boolean promoted = false;
        for (var e : newFlows.entrySet()) {
            FlowEntry fe = e.getValue();
            if (fe.isSource) continue;
            int slot = e.getKey();
            FluidFlowBehavior behavior = FluidFlowBehaviors.of(fe.fluid);
            if (behavior.shouldPromote(slot, countSourceNeighbors(newFlows, slot, containerSize, width))) {
                generatedSources.put(slot, fe.fluid);
                promoted = true;
            }
        }
        return promoted;
    }

    /** 该格四邻中已是源的个数（晋升判定的输入）。 */
    private static int countSourceNeighbors(Map<Integer, FlowEntry> flows, int slot, int containerSize, int width) {
        int count = 0;
        for (int n : ContainerContext.getNeighbors(slot, containerSize, width)) {
            FlowEntry fe = flows.get(n);
            if (fe != null && fe.isSource) count++;
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
    private void pushItems(ContainerContext ctx, int containerSize, int width) {
        Map<Integer, List<Integer>> downstream = new HashMap<>();
        for (var entry : flows.entrySet()) {
            int slot = entry.getKey();
            FlowEntry fe = entry.getValue();
            if (fe.fromSlot >= 0) {
                downstream.computeIfAbsent(fe.fromSlot, k -> new ArrayList<>()).add(slot);
            }
        }

        List<Map.Entry<Integer, FlowEntry>> sorted = new ArrayList<>(flows.entrySet());
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

    public Map<Integer, int[]> exportFlowData() {
        Map<Integer, int[]> map = new LinkedHashMap<>();
        for (var e : flows.entrySet()) {
            FlowEntry fe = e.getValue();
            map.put(e.getKey(), new int[]{fe.level, fe.fromSlot});
        }
        return map;
    }
}