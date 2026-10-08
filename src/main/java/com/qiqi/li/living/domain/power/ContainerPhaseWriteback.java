package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 相位快照的写回钩子（电力领域）—— 见 {@link ContainerTickHook}。
 *
 * <p>原逻辑硬编码在 {@code ContainerLivingItemHandler#writebackBlockEntities}（2026-10-08
 * 计划 ⑤ 移入本领域）：每 tick 末把锁相状态冻结到 BE 附件（带 Codec 落盘）。快照只含已锁相
 * 且存活窗口内的边（worthSaving 过滤），稳态无振荡器时为 EMPTY —— 零成本。退出重进 /
 * LRU 回收后由 {@code getPowerData} 回填，相位无缝续接。</p>
 *
 * <p>⚠️ 时钟与 {@code tickContainerData} 的 {@code resolvePhaseClock} 同源（世界 game time
 * 优先，回退本地轴）—— 快照必须存与驱动同坐标系的值。</p>
 */
public final class ContainerPhaseWriteback implements ContainerTickHook {

    @Override
    public void onWriteback(TickableContainerContext ctx, TickContext tick) {
        ContainerPowerData powerData = tick.data(ContainerPowerData.KEY);
        if (powerData == null || ctx.getAssociatedBlockEntities().isEmpty()) return;

        long clock = powerData.currentTick();   // 回退轴（无 Level / 测试环境）
        for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
            Level beLevel = be.getLevel();
            if (beLevel != null && !beLevel.isClientSide()) {
                clock = beLevel.getGameTime();
                break;
            }
        }
        PhaseSnapshot snapshot = PhaseSnapshot.capture(powerData, clock);
        for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
            be.setData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value(), snapshot);
        }
    }
}
