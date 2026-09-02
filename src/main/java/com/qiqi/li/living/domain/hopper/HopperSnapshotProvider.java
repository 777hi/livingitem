package com.qiqi.li.living.domain.hopper;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.MutableSnapshot;
import com.qiqi.li.living.container.SnapshotProvider;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.world.item.ItemStack;

/**
 * 活漏斗快照贡献者 —— 计算本容器每个活漏斗的源/目标槽位与过滤数据。
 *
 * <p>原逻辑位于 {@code ContainerSnapshot.capture}，此处迁回 domain 包，
 * 解除 {@code container} 包对 {@code domain.hopper} 的依赖。</p>
 */
public class HopperSnapshotProvider implements SnapshotProvider {

    @Override
    public void contribute(ContainerContext ctx, TickContext tick, int containerSize, int width, MutableSnapshot builder) {
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = ctx.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!LivingHopperFunction.isLivingHopper(stack)) continue;

            LivingHopperData data = LivingItemManager.getHopperData(stack);
            if (data == null) continue;

            var dir = data.direction();
            builder.sourceOf[slot] = SlotResolver.resolve(slot, dir.sourceOffset(), containerSize, width);
            builder.targetOf[slot] = SlotResolver.resolve(slot, dir.targetOffset(), containerSize, width);
        }

        builder.filterOf = HopperFilterBuilder.buildAll(ctx, containerSize, builder.sourceOf, builder.targetOf);
    }
}
