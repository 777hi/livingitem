package com.qiqi.li.living.domain.chest;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.container.MutableSnapshot;
import com.qiqi.li.living.container.SnapshotProvider;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.world.item.ItemStack;

/**
 * 活箱子快照贡献者 —— 计算每个活箱子的占用槽位/字节快照。
 *
 * <p>原逻辑位于 {@code ContainerSnapshot.capture}（buildAllChestSnapshots），此处迁回 domain 包，
 * 解除 {@code container} 包对 {@code domain.chest} 的依赖。</p>
 */
public class ChestSnapshotProvider implements SnapshotProvider {

    @Override
    public void contribute(ContainerContext ctx, TickContext tick, int containerSize, int width, MutableSnapshot builder) {
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack stack = ctx.getItem(slot);
            if (LivingChestFunction.isLivingChest(stack)) {
                int usedSlots = LivingChestFunction.countUsedSlots(stack);
                int usedBytes = LivingChestFunction.getCurrentByteUsage(stack);
                boolean isFull = usedSlots >= LivingChestFunction.CHEST_SLOTS;
                boolean isByteFull = usedBytes >= LivingChestFunction.MAX_STORAGE_BYTES;
                builder.chestOf[slot] = new ContainerSnapshot.ChestSnapshot(usedSlots, usedBytes, isFull, isByteFull);
            }
        }
    }
}
