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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        public void registerSource(int slot) { }
        @Override
        public void registerSource(int slot, FluidType fluid) { }
        @Override
        public void removeSource(int slot) { }
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

    /** 默认流体 = 水（1b-1 兼容：旧调用点 {@link #registerSource(int)} 未指定流体时按水处理）。 */
    private static FluidType defaultFluid() {
        return Fluids.WATER.getFluidType();
    }

    public static final int SOURCE_LEVEL = 0;
    public static final int MAX_FLOW_LEVEL = 7;
    public static final int FLOW_STEP_TICKS = 4;

    public static class FlowEntry {
        int level;
        boolean isSource;
        int fromSlot;
        /** 该格流体类型（1b-1：单张 map 带类型 —— 一个槽位只装一种流体，类比一个方块位置）。 */
        final FluidType fluid;

        /** 兼容构造器：未指定流体时按水处理（旧调用点）。 */
        public FlowEntry(int level, boolean isSource, int fromSlot) {
            this(level, isSource, fromSlot, defaultFluid());
        }

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
     * 派生源（活水源，2026-10-03）—— <b>容器级永久资产</b>：槽位 → 源流体类型。
     *
     * <p>与「桶源」（活水桶在场时由桶注册，见 {@code LivingWaterBucketFunction.tick}）并列的
     * 第二种源：由倒水 / 晋升创建，<b>不随水桶离开消失</b>（原版语义：源是真实方块）。
     * 每 tick 在 {@link #recalculate} 播种阶段并入 BFS；生命周期：
     * 倒入/晋升而生，挤没（任何活物品进入该格）/汲走而死，容器销毁随 attachment 湮灭。</p>
     *
     * <p>⚠️ 必须记住流体类型 —— 否则重播种时岩浆源会退化成水。</p>
     */
    private final Map<Integer, FluidType> generatedSources = new LinkedHashMap<>();
    private long lastTickTime;
    private int tickCounter;

    public Map<Integer, FlowEntry> getFlows() {
        return flows;
    }

    public boolean isEmpty() {
        return flows.isEmpty();
    }

    public long getLastTickTime() {
        return lastTickTime;
    }

    public void setLastTickTime(long time) {
        this.lastTickTime = time;
    }

    /** 注册一个源（默认水 —— 兼容旧调用点）。 */
    public void registerSource(int slot) {
        registerSource(slot, defaultFluid());
    }

    /**
     * 注册一个指定流体类型的源。
     *
     * <p>同槽位换流体 ⇒ <b>整条覆盖</b>（一槽只装一种流体）。</p>
     */
    public void registerSource(int slot, FluidType fluid) {
        FlowEntry existing = flows.get(slot);
        if (existing != null && existing.fluid == fluid) {
            existing.isSource = true;
            existing.level = SOURCE_LEVEL;
            existing.fromSlot = -1;
        } else {
            flows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, fluid));
        }
    }

    public void removeSource(int slot) {
        FlowEntry entry = flows.get(slot);
        if (entry != null) {
            entry.isSource = false;
        }
    }

    /**
     * 【查】指定槽位是否是流体源（供汲 / 倒处理器与渲染端查询）。
     *
     * <p>注意：源是<b>容器级资产</b>，与「槽位里有没有活物品」无关 —— 桶源由桶的物品驱动注册，
     * 生成源由流体侧注册，二者在本表里等价。</p>
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

        recalculate(containerSize, width, ctx);
        transformSourceItems(ctx); // 1b-2 接缝：每流体拍在源格问行为「是否转化」

        if (tickCounter % FLOW_STEP_TICKS == 0) {
            pushItems(ctx, containerSize, width);
        }
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
                ctx.setItem(slot, transformed);
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
    private void recalculate(int containerSize, int width, ContainerContext ctx) {
        Map<Integer, FlowEntry> newFlows;
        // 晋升收敛循环（1b-2 接缝）：升格为源后水网会扩张 ⇒ 重跑 BFS，直到无新升格。
        // 默认行为不晋升（shouldPromote 恒 false）⇒ 恰好一轮，与旧行为一致。
        while (true) {
            newFlows = spread(containerSize, width, ctx);
            if (!tryPromote(newFlows, containerSize, width)) break;
        }
        flows.clear();
        flows.putAll(newFlows);
    }

    /** 播种（桶源 + 派生源）+ BFS 扩散**一轮**，返回流动表（不改 {@link #flows}）。 */
    private Map<Integer, FlowEntry> spread(int containerSize, int width, ContainerContext ctx) {
        Map<Integer, FlowEntry> newFlows = new LinkedHashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();

        // 播种①：桶源（活水桶在场；桶源退役批次二后此段随 isLivingBucketOf 一并删除）
        for (var entry : flows.entrySet()) {
            FlowEntry src = entry.getValue();
            if (!src.isSource) continue;
            int slot = entry.getKey();
            // 源的存活判定：槽位里仍是「装着该流体的活桶」（1b-1：仅水）
            if (!isLivingBucketOf(ctx.getItem(slot), src.fluid)) continue;
            newFlows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, src.fluid));
            queue.add(slot);
        }

        // 播种②：派生源（活水源）—— 无条件并入，不依赖任何物品在场。
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
     * 该物品是否是「装着指定流体的活桶」—— 源的存活判定。
     *
     * <p><b>当前仅水。</b>多流体 / 模组流体的「桶内容判定」属<b>流体侧</b>
     * （改读 NeoForge 的 {@code FluidStack} / {@code SimpleFluidContent}，而非硬编码水桶）。</p>
     */
    private static boolean isLivingBucketOf(ItemStack item, FluidType fluid) {
        return fluid == defaultFluid()
            && item.is(Items.WATER_BUCKET)
            && LivingItemManager.isLivingItem(item);
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