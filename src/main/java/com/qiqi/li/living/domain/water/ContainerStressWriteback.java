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
            return;
        }

        // 物品形态（活背包）：没有 BE、也没有背包 ⇒ 借宿主锚点输出 —— **玩法开关，默认关**。
        // 关掉时算出的应力只显示在 tooltip，不进入 Create 网络（tooltip 显示的是理论产能，
        // 与「有没有接上网络」无关，所以这不矛盾）。
        if (ctx.getAssociatedBlockEntities().isEmpty() && ctx.getInventory() == null
                && com.qiqi.li.living.config.GameplayConfig.waterWheelUsesHostAnchor()) {
            applyAtHostAnchor(ctx, stressData);
        }
    }

    /**
     * 宿主锚点 → 应力注入点。
     *
     * <p>{@code StressOutputManager#apply} 内部取 {@code pos.below()}，所以这里给的是
     * <b>容器/宿主所在的位置</b>，不是齿轮本身：</p>
     * <ul>
     *   <li>方块宿主 ⇒ 宿主方块（注入到它下方那格）</li>
     *   <li>玩家宿主 ⇒ {@code player.blockPosition()}（注入到脚下那格 —— 与背包形态一致）</li>
     *   <li>实体宿主（船/矿车）⇒ 实体所在格下方（多数情况没东西，语义一致即可）</li>
     * </ul>
     */
    private static void applyAtHostAnchor(TickableContainerContext ctx, ContainerStressData stressData) {
        com.qiqi.li.living.container.ContainerAnchor anchor = ctx.getHostAnchor();
        if (anchor == null) return;
        if (anchor instanceof com.qiqi.li.living.container.ContainerAnchor.AtBlock block) {
            StressOutputManager.apply(ctx.getLevel(), block.pos(), stressData);
        } else if (anchor instanceof com.qiqi.li.living.container.ContainerAnchor.AtPlayer at) {
            StressOutputManager.apply(at.player().level(), at.player().blockPosition(), stressData);
        } else if (anchor instanceof com.qiqi.li.living.container.ContainerAnchor.AtEntity entityAnchor) {
            var entity = entityAnchor.entity();
            StressOutputManager.apply(entity.level(), entity.blockPosition(), stressData);
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
