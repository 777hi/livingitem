package com.qiqi.li.living.domain.redstone;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.MutableSnapshot;
import com.qiqi.li.living.container.SnapshotProvider;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 红电快照贡献者 —— 计算每个槽位的红电元件类型位图（{@code redstoneMaskOf}）与铜氧化等级（{@code capOf}）。
 *
 * <p>原逻辑位于 {@link ContainerRedstoneData#buildSlotMask}，此处迁回 domain 包，
 * 解除 {@code container} 包对 {@code domain.redstone} 的依赖，并让红电位图随容器快照一起
 * 跨 tick 缓存（物品未变更时直接复用，无需每 tick 重扫）。</p>
 *
 * <p>类型位图通过 {@link TickContext#getFunctionSlots} 读取活跃槽位集合得到，与
 * {@code buildSlotMask} 原先的来源完全一致，行为等价。</p>
 */
public class RedstoneSnapshotProvider implements SnapshotProvider {

    @Override
    public void contribute(ContainerContext ctx, TickContext tick, int containerSize, int width, MutableSnapshot builder) {
        Set<Integer> torchSlots = tick.getFunctionSlots(LivingRedstoneTorchFunction.ID);
        Set<Integer> dustSlots = tick.getFunctionSlots(LivingRedstoneFunction.ID);
        Set<Integer> buttonSlots = tick.getFunctionSlots(LivingButtonFunction.ID);
        Set<Integer> leverSlots = tick.getFunctionSlots(LivingLeverFunction.ID);
        Set<Integer> lampSlots = tick.getFunctionSlots(LivingRedstoneLampFunction.ID);
        Set<Integer> repeaterSlots = tick.getFunctionSlots(LivingRepeaterFunction.ID);
        Set<Integer> comparatorSlots = tick.getFunctionSlots(LivingComparatorFunction.ID);
        Set<Integer> redstoneBlockSlots = tick.getFunctionSlots(LivingRedstoneBlockFunction.ID);
        Set<Integer> copperSlots = tick.getFunctionSlots(LivingCopperFunction.ID);

        Set<Integer> chiseledSlots = copperSubset(copperSlots, ctx, LivingCopperFunction::isChiseled);
        Set<Integer> cutSlots = copperSubset(copperSlots, ctx, LivingCopperFunction::isCut);
        Set<Integer> grateSlots = copperSubset(copperSlots, ctx, LivingCopperFunction::isGrate);
        Set<Integer> bulbSlots = copperSubset(copperSlots, ctx, LivingCopperFunction::isBulb);

        builder.markRedstone(dustSlots, ContainerRedstoneData.BIT_DUST);
        builder.markRedstone(torchSlots, ContainerRedstoneData.BIT_TORCH);
        builder.markRedstone(buttonSlots, ContainerRedstoneData.BIT_BUTTON);
        builder.markRedstone(leverSlots, ContainerRedstoneData.BIT_LEVER);
        builder.markRedstone(lampSlots, ContainerRedstoneData.BIT_LAMP);
        builder.markRedstone(repeaterSlots, ContainerRedstoneData.BIT_REPEATER);
        builder.markRedstone(comparatorSlots, ContainerRedstoneData.BIT_COMPARATOR);
        builder.markRedstone(redstoneBlockSlots, ContainerRedstoneData.BIT_BLOCK);
        builder.markRedstone(copperSlots, ContainerRedstoneData.BIT_COPPER);
        builder.markRedstone(chiseledSlots, ContainerRedstoneData.BIT_CHISELED);
        builder.markRedstone(cutSlots, ContainerRedstoneData.BIT_CUT);
        builder.markRedstone(grateSlots, ContainerRedstoneData.BIT_GRATE);
        builder.markRedstone(bulbSlots, ContainerRedstoneData.BIT_BULB);

        for (int slot : copperSlots) {
            builder.capOf(slot, LivingCopperFunction.getOxidationLevel(ctx.getItem(slot).getItem()));
        }
    }

    private static Set<Integer> copperSubset(Set<Integer> copperSlots, ContainerContext context,
            Predicate<Item> predicate) {
        Set<Integer> result = new HashSet<>();
        for (int slot : copperSlots) {
            ItemStack stack = context.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack.getItem())) {
                result.add(slot);
            }
        }
        return result;
    }
}
