package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.compat.create.StressOutputManager;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 应力数据的写回钩子（活水车领域）—— 见 {@link ContainerTickHook}。
 *
 * <p>原逻辑硬编码在 {@code ContainerLivingItemHandler#writebackBlockEntities} /
 * {@code updateStressOutput} / {@code updatePlayerFeetStressOutput}（2026-10-08 计划 ⑤
 * 移入本领域）：把本 tick 的应力写回 BE 附件，并交给 Create 应力输出；玩家背包无 BE
 * ⇒ 落到玩家脚底。</p>
 */
public final class ContainerStressWriteback implements ContainerTickHook {

    @Override
    public void onWriteback(TickableContainerContext ctx, TickContext tick) {
        ContainerStressData stressData = tick.data(ContainerStressData.KEY);
        if (stressData == null) return;

        for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
            be.setData(WaterComponents.CONTAINER_STRESS_DATA.value(), stressData);
            StressOutputManager.apply(be.getLevel(), be.getBlockPos(), stressData);
        }

        if (ctx.getAssociatedBlockEntities().isEmpty() && ctx.getInventory() != null) {
            updatePlayerFeetStressOutput(ctx, stressData);
        }
    }

    private static void updatePlayerFeetStressOutput(TickableContainerContext ctx,
                                                     ContainerStressData stressData) {
        Inventory inventory = ctx.getInventory();
        if (inventory == null) return;
        Player player = inventory.player;
        Level level = player.level();
        if (level.isClientSide) return;

        BlockPos feetPos = player.blockPosition();
        StressOutputManager.apply(level, feetPos, stressData);
    }
}
