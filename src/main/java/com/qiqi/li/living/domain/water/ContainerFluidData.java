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
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;

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

    public void tick(ContainerContext ctx) {
        int containerSize = ctx.getSize();
        int width = ctx.getWidth();
        if (containerSize <= 0 || width <= 0) return;

        tickCounter++;

        recalculate(containerSize, width, ctx);

        if (tickCounter % FLOW_STEP_TICKS == 0) {
            pushItems(ctx, containerSize, width);
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
        Map<Integer, FlowEntry> newFlows = new LinkedHashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();

        for (var entry : flows.entrySet()) {
            FlowEntry src = entry.getValue();
            if (!src.isSource) continue;
            int slot = entry.getKey();
            // 源的存活判定：槽位里仍是「装着该流体的活桶」（1b-1：仅水）
            if (!isLivingBucketOf(ctx.getItem(slot), src.fluid)) continue;
            newFlows.put(slot, new FlowEntry(SOURCE_LEVEL, true, -1, src.fluid));
            queue.add(slot);
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

        flows.clear();
        flows.putAll(newFlows);
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