package com.qiqi.li.living.function;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.LivingItemFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.ContainerFluidData;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.data.LivingWaterBucketData;
import com.qiqi.li.living.data.WaterData;

public class LivingWaterBucketFunction implements LivingItemFunction {

    public static final String ID = "living_water_bucket";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= context.getSize()) continue;

            ItemStack stack = entry.stack();
            LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
            WaterData water = data.water();

            long gameTime = level.getGameTime();
            String containerKey = context.getContainerKey();
            int containerWidth = context.getWidth();

            boolean needsReset = false;
            if (water.lastTick() >= 0 && gameTime - water.lastTick() > 2) {
                needsReset = true;
            }
            if (containerKey != null && !containerKey.equals(water.containerKey())) {
                needsReset = true;
            }
            if (water.hostSlot() >= 0 && water.hostSlot() != slot) {
                needsReset = true;
            }

            ContainerSnapshot snapshot = tick.snapshot();
            ContainerFluidData fluidData = snapshot != null ? snapshot.getFluidData() : null;

            if (needsReset && fluidData != null && water.hostSlot() >= 0) {
                fluidData.removeSource(water.hostSlot());
            }

            water = new WaterData(
                gameTime, slot, slot % Math.max(1, containerWidth),
                slot / Math.max(1, containerWidth), Math.max(1, containerWidth),
                containerKey != null ? containerKey : water.containerKey(),
                water.flow()
            );

            if (fluidData != null) {
                fluidData.registerSource(slot);
            }

            LivingItemManager.setWaterBucketData(stack, data.withWater(water));
            context.syncSlotToClients(slot, stack);
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
            tooltipAdder.accept(Component.literal(
                "水流: " + count + " 格 (最远" + maxLevel + "格)")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        }
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isLivingWaterBucket(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) && LivingItemManager.isLivingItem(stack);
    }

    public static void postTickSync(ContainerContext ctx, ContainerFluidData fluidData) {
        String flowStr = buildFlowString(fluidData);
        for (int i = 0; i < ctx.getSize(); i++) {
            ItemStack stack = ctx.getItem(i);
            if (!isLivingWaterBucket(stack)) continue;

            LivingWaterBucketData data = LivingItemManager.getWaterBucketData(stack);
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
            sb.append(e.getKey()).append(':').append(e.getValue().level());
        }
        return sb.toString();
    }
}