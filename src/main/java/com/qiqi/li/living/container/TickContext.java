package com.qiqi.li.living.container;

import java.util.HashSet;
import java.util.Set;

/**
 * Tick 级上下文 —— 每次容器 tick 时创建的临时状态。
 *
 * <p>与 {@link ContainerContext} 不同，此对象的生命周期仅为单次 tick。
 * 包含：
 * <ul>
 *   <li>槽位互斥集合 —— 防止多个活熔炉处理同一输入槽位</li>
 *   <li>级联传输防护 —— 防止同 tick 内漏斗链级联传输</li>
 *   <li>容器快照 —— 预扫描的容器信息（活漏斗连接图等）</li>
 *   <li>流体数据 —— 容器关联的流体状态</li>
 * </ul>
 *
 * <p>由 {@link ContainerLivingItemHandler} 在每次 tick 开始时创建，
 * tick 结束后丢弃。</p>
 */
public record TickContext(
    Set<String> occupiedSlots,
    Set<Integer> transferredTargetSlots,
    ContainerSnapshot snapshot,
    ContainerFluidData fluidData
) {

    /**
     * 创建空的 TickContext（用于测试）。
     */
    public static TickContext empty() {
        return new TickContext(
            new HashSet<>(),
            new HashSet<>(),
            ContainerSnapshot.EMPTY,
            ContainerFluidData.EMPTY
        );
    }

    /**
     * 为容器创建 TickContext。
     *
     * @param ctx 容器上下文
     * @return 新的 TickContext
     */
    public static TickContext create(ContainerContext ctx) {
        // 扫描流体数据（如果有）
        ContainerFluidData fluidData = ContainerFluidData.EMPTY;
        if (ctx instanceof SimpleContainerContext simpleCtx) {
            fluidData = simpleCtx.getOrCreateFluidData();
        }

        // 捕获快照（包含活漏斗连接图等）
        ContainerSnapshot snapshot = ContainerSnapshot.capture(ctx, fluidData);

        return new TickContext(
            new HashSet<>(),
            new HashSet<>(),
            snapshot,
            fluidData
        );
    }
}