package com.qiqi.li.living.domain.water;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.LivingWaterBucketData;
import com.qiqi.li.living.domain.water.WaterData;

/**
 * 活水桶功能 —— 模拟原版水流在容器内的蔓延与推动。
 *
 * <h3>性能优化：瞬态状态服务端缓存</h3>
 * <p>水桶的 {@code lastTick}、{@code hostSlot}、{@code containerKey} 等瞬态字段
 * 仅用于服务端 {@code needsReset} 检测，不需要每 tick 写入 DataComponent。
 * 这些字段缓存在 {@link #BUCKET_STATES} 中，避免每 tick 调用
 * {@code LivingItemManager.setWaterBucketData()} 修改 ItemStack 的 DataComponent，
 * 从而消除玩家背包中活水桶的性能开销（DataComponent 写入 + 网络同步包）。</p>
 *
 * <p>DataComponent 仅在 {@code flow} 实际变化时（{@link #postTickSync}）才写入，
 * 大幅减少网络包数量。</p>
 */
public class LivingWaterBucketFunction implements LivingItemFunction, HasContainerData {

    public static final String ID = "living_water_bucket";

    private static final long STALE_THRESHOLD_MS = 120_000;

    private record BucketState(long lastTick, int hostSlot, String containerKey) {}

    private static final Map<String, BucketState> BUCKET_STATES = new HashMap<>();

    private static String trackingKey(String containerKey, int slot) {
        return containerKey + ":" + slot;
    }

    public static void clearAllCaches() {
        BUCKET_STATES.clear();
    }

    static void cleanupStaleEntries(long currentTimeMs) {
        // handled by ContainerLivingItemHandler's periodic cleanup
    }

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        long gameTime = level.getGameTime();
        String containerKey = context.getContainerKey();
        int containerWidth = context.getWidth();
        ContainerFluidData fluidData = tick.fluidData;

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= context.getSize()) continue;

            String key = containerKey != null ? trackingKey(containerKey, slot) : null;
            BucketState prev = key != null ? BUCKET_STATES.get(key) : null;

            long prevLastTick = prev != null ? prev.lastTick : -1L;
            int prevHostSlot = prev != null ? prev.hostSlot : -1;
            String prevContainerKey = prev != null ? prev.containerKey : null;

            boolean needsReset = false;
            if (prevLastTick >= 0 && gameTime - prevLastTick > 2) {
                needsReset = true;
            }
            if (containerKey != null && !containerKey.equals(prevContainerKey)) {
                needsReset = true;
            }
            if (prevHostSlot >= 0 && prevHostSlot != slot) {
                needsReset = true;
            }

            if (key != null) {
                BUCKET_STATES.put(key, new BucketState(gameTime, slot,
                    containerKey != null ? containerKey : ""));
            }

            if (fluidData == ContainerFluidData.EMPTY) continue;

            if (needsReset && prevHostSlot >= 0) {
                fluidData.removeSource(prevHostSlot);
            }

            fluidData.registerSource(slot);
        }
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
        String flow = data.water().flow();
        int count = 0;
        int maxLevel = 0;
        if (!flow.isEmpty()) {
            for (String part : flow.split(",")) {
                String[] kv = part.split(":");
                if (kv.length >= 2) {
                    count++;
                    int lvl = Integer.parseInt(kv[1]);
                    if (lvl > maxLevel) maxLevel = lvl;
                }
            }
        }
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_bucket.status"));
        if (count > 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.water_bucket.flow",
                count, maxLevel).withStyle(net.minecraft.ChatFormatting.AQUA));
        }
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerFluidData fluidData = tick.fluidData;
        if (fluidData == null || fluidData == ContainerFluidData.EMPTY) return;
        if (!fluidData.isEmpty()) {
            fluidData.setLastTickTime(System.currentTimeMillis());
            fluidData.tick(ctx);
        }
        postTickSync(ctx, fluidData, entries);
    }

    public static boolean isLivingWaterBucket(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(stack);
    }

    public static void postTickSync(ContainerContext ctx, ContainerFluidData fluidData,
        List<SlotEntry> waterBucketEntries) {
        if (waterBucketEntries.isEmpty()) return;
        String flowStr = fluidData != null ? buildFlowString(fluidData) : "";
        for (SlotEntry entry : waterBucketEntries) {
            int i = entry.slotIndex();
            ItemStack stack = ctx.getItem(i);
            if (!isLivingWaterBucket(stack)) continue;

            LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
            String oldFlow = data.water().flow();
            if (oldFlow.equals(flowStr)) continue;

            WaterData water = data.water().withFlow(flowStr);
            LivingItemManager.setWaterBucketData(stack, data.withWater(water));
            ctx.syncSlotToClients(i, stack);
        }
    }

    private static String buildFlowString(ContainerFluidData fluidData) {
        var flows = fluidData.getFlows();
        if (flows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : flows.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue().level()).append(':').append(e.getValue().fromSlot());
        }
        return sb.toString();
    }
}