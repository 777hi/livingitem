package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 容器级红电账本的领域访问器 —— 原为 {@code ContainerLivingItemHandler#getPowerData}
 * （2026-10-08 计划 ⑤ 移入本领域）。
 *
 * <p>账本首次创建时（退出重进 / LRU 回收后首访），尝试从 BE 附件的 {@link PhaseSnapshot}
 * 回填锁相状态。回填基准时钟取 BE 所在世界的 game time（2026-09-11 换轴：与 capture
 * 同坐标系 —— 快照存的就是退出前的世界 tick，同轴回填后 φ 与快照一致，首跳 interval=P
 * 精确续接不再重锚）；Level 不可达时回退容器本地轴。快照为空（首次使用 / 无振荡器）
 * 则保持默认 warmup（首拍无沿宽限）。见 {@link PhaseSnapshot} javadoc。</p>
 *
 * <p>⚠️ <b>迁移时的既有状态</b>：原 {@code getPowerData} 全仓库<b>零调用者</b>
 * ⇒ 快照「只写（{@code ContainerPhaseWriteback}）不读」—— 回填路径当前未生效。
 * 本次<b>只做归属迁移、不改变调用行为</b>；是否接上（在账本创建处调用本方法）
 * 属功能决策，留给后续拍板。</p>
 */
public final class ContainerPowerAccess {

    private ContainerPowerAccess() {}

    /**
     * 取或创建容器级红电账本（创建时尝试从 BE 附件的相位快照回填锁相状态）。
     *
     * @return 账本；容器不支持容器级数据时返回 {@code null}
     */
    public static ContainerPowerData getOrCreatePowerData(ContainerContext ctx) {
        ContainerPowerData power = ctx.peekContainerData(ContainerPowerData.KEY);
        if (power != null) return power;

        power = ctx.getOrCreateContainerData(ContainerPowerData.KEY);
        // 相位快照回填（2026-09-09）：BE 附件里存有退出前的锁相状态则无缝续接
        if (ctx instanceof SimpleContainerContext simpleCtx) {
            long base = 0;
            boolean hasWorldClock = false;
            for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                PhaseSnapshot snapshot = be.getData(LivingComponents.CONTAINER_PHASE_SNAPSHOT);
                if (snapshot == null) continue;
                // 世界轴基准（与 capture 同源）：服务端 BE 挂着 Level 才可信
                if (!hasWorldClock) {
                    Level beLevel = be.getLevel();
                    if (beLevel != null && !beLevel.isClientSide()) {
                        base = beLevel.getGameTime();
                        hasWorldClock = true;
                    }
                }
                long clock = hasWorldClock ? base : power.currentTick();
                if (snapshot.restoreInto(power, clock) > 0) break;
            }
        }
        return power;
    }
}
