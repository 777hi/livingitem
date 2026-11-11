package com.qiqi.li.living.domain.water;

import com.qiqi.li.living.compat.create.StressOutputManager;
import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

import javax.annotation.Nullable;

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
            // 只走 Create 直传：应力是 tick 级数据（每 tick 重算），没有也不该有落盘附件
            StressOutputManager.apply(be.getLevel(), be.getBlockPos(), stressData);
        }

        if (ctx.getAssociatedBlockEntities().isEmpty() && ctx.getInventory() != null) {
            updatePlayerFeetStressOutput(ctx, stressData);
            return;
        }

        // 无 BE、无玩家背包：实体容器用自己的位置（含掉落物）—— 与方块容器同判，不设门；
        // 物品形态（活背包）没有自己的位置 ⇒ 借宿主锚点 —— **玩法开关，默认关**（D-container-16）。
        // 关掉时算出的应力只显示在 tooltip，不进入 Create 网络（tooltip 显示的是理论产能，
        // 与「有没有接上网络」无关，所以这不矛盾）。
        if (ctx.getAssociatedBlockEntities().isEmpty() && ctx.getInventory() == null) {
            ContainerAnchor anchor = selfOrBorrowedHostAnchor(ctx);
            if (anchor instanceof ContainerAnchor.AtBlock block) {
                StressOutputManager.apply(ctx.getLevel(), block.pos(), stressData);
            } else if (anchor instanceof ContainerAnchor.AtPlayer at) {
                StressOutputManager.apply(at.player().level(), at.player().blockPosition(), stressData);
            } else if (anchor instanceof ContainerAnchor.AtEntity entityAnchor) {
                StressOutputManager.apply(entityAnchor.entity().level(),
                    entityAnchor.entity().blockPosition(), stressData);
            }
        }
    }

    /**
     * 无 BE、无玩家背包时的锚点：实体容器 ⇒ 自己的位置（{@code getAnchor()}，含掉落物）；
     * 物品形态（活背包）⇒ 借宿主锚点（{@code host_anchor.water_wheel}，默认关）。
     *
     * <p>口径：自己的位置直接用，借来的位置才要开关（D-container-25）。
     * 取点仍由调用方定：{@code StressOutputManager#apply} 内部取 {@code pos.below()}。</p>
     */
    // 包级可见：供单测验「实体 / 物品形态双路径」（ContainerStressWritebackTest）。
    @Nullable
    static ContainerAnchor selfOrBorrowedHostAnchor(TickableContainerContext ctx) {
        ContainerAnchor self = ctx.getAnchor();
        if (self != null) {
            return self;
        }
        return com.qiqi.li.living.config.GameplayConfig.waterWheelUsesHostAnchor()
            ? ctx.getHostAnchor() : null;
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
