package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

import net.neoforged.neoforge.attachment.IAttachmentHolder;

import net.minecraft.world.level.Level;

import java.util.List;

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
        if (powerData == null) return;

        // 能力存在性的**负边沿兜底**（2026-10-31）：容器里最后一个涂蜡铜块被拿走时
        // LivingWaxedCopperFunction 已不在 grouped 里（不再 tick），没人看得到「灯没了」
        // ⇒ 只能在这里撤名。只在「正在通告」时扫，稳态零成本。
        if (powerData.isCapabilityAdvertised() && !ContainerEnergyStorage.hasLivingBulb(ctx)) {
            ContainerEnergyStorage.announcePresence(ctx, powerData, false);
        }

        // 持久宿主（BE / 实体附件，2026-10-21 实体容器接入）—— 空 = 无宿主可写（掉落物等）即返回
        List<? extends IAttachmentHolder> hosts = ctx.getDataHosts();
        if (hosts.isEmpty()) return;

        long clock = powerData.currentTick();   // 回退轴（无 Level / 测试环境）
        Level ctxLevel = ctx.getLevel();
        if (ctxLevel != null && !ctxLevel.isClientSide()) clock = ctxLevel.getGameTime();

        PhaseSnapshot snapshot = PhaseSnapshot.capture(powerData, clock);
        for (var host : hosts) {
            host.setData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value(), snapshot);
        }
    }
}
