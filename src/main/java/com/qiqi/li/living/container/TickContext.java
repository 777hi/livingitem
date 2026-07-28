package com.qiqi.li.living.container;

import java.util.HashSet;
import java.util.Set;

import com.qiqi.li.living.perf.PerfMetrics;

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
 * tick 结束后通过 {@link #release()} 归还到对象池。</p>
 */
public class TickContext {

    /** 对象池 —— 复用 TickContext 实例，减少 GC 压力。 */
    private static final ThreadLocal<TickContextPool> POOL = ThreadLocal.withInitial(TickContextPool::new);

    // 可变字段（对象池复用需要）
    public final Set<String> occupiedSlots = new HashSet<>();
    public final Set<Integer> transferredTargetSlots = new HashSet<>();
    public ContainerFluidData fluidData = ContainerFluidData.EMPTY;

    private ContainerContext ctx;
    private ContainerSnapshot _snapshot = ContainerSnapshot.EMPTY;
    private boolean snapshotBuilt = false;

    /**
     * 获取容器快照（延迟构建）。
     * 仅在首次访问时调用 {@link ContainerSnapshot#capture}，避免对闲置容器产生开销。
     */
    public ContainerSnapshot getSnapshot() {
        if (!snapshotBuilt) {
            _snapshot = ContainerSnapshot.capture(ctx, fluidData);
            snapshotBuilt = true;
        }
        return _snapshot;
    }

    /**
     * 从对象池获取 TickContext（如果池中有可用实例则复用，否则创建新实例）。
     */
    public static TickContext acquire(ContainerContext ctx) {
        return POOL.get().acquire(ctx);
    }

    /**
     * 将 TickContext 归还到对象池，清空状态以便下次复用。
     */
    public void release() {
        POOL.get().release(this);
    }

    /**
     * 创建空的 TickContext（用于测试）。
     */
    public static TickContext empty() {
        TickContext ctx = new TickContext();
        ctx.occupiedSlots.clear();
        ctx.transferredTargetSlots.clear();
        ctx._snapshot = ContainerSnapshot.EMPTY;
        ctx.snapshotBuilt = false;
        ctx.fluidData = ContainerFluidData.EMPTY;
        return ctx;
    }

    /**
     * 重置此 TickContext 的状态（内部使用，对象池调用）。
     */
    void reset(ContainerContext ctx) {
        occupiedSlots.clear();
        transferredTargetSlots.clear();

        this.ctx = ctx;
        this._snapshot = ContainerSnapshot.EMPTY;
        this.snapshotBuilt = false;

        ContainerFluidData fluidData = ContainerFluidData.EMPTY;
        if (ctx instanceof SimpleContainerContext simpleCtx) {
            fluidData = simpleCtx.getOrCreateFluidData();
        }
        this.fluidData = fluidData;
    }

    /**
     * 清空此 TickContext 的所有状态（归还到对象池前调用）。
     */
    void clear() {
        occupiedSlots.clear();
        transferredTargetSlots.clear();
        ctx = null;
        _snapshot = ContainerSnapshot.EMPTY;
        snapshotBuilt = false;
        fluidData = ContainerFluidData.EMPTY;
    }

    /**
     * 简单的对象池实现。
     */
    private static class TickContextPool {
        private static final int MAX_POOL_SIZE = 4;
        private final TickContext[] pool = new TickContext[MAX_POOL_SIZE];
        private int size = 0;

        /**
         * 获取 TickContext（池中有则复用，否则创建新实例）。
         */
        TickContext acquire(ContainerContext ctx) {
            if (size > 0) {
                TickContext tick = pool[--size];
                pool[size] = null;
                tick.reset(ctx);
                PerfMetrics.recordPoolHit(true);
                return tick;
            }
            PerfMetrics.recordPoolHit(false);
            return createNew(ctx);
        }

        /**
         * 归还 TickContext 到池中（如果池未满）。
         */
        void release(TickContext tick) {
            tick.clear();
            if (size < MAX_POOL_SIZE) {
                pool[size++] = tick;
            }
        }

        private static TickContext createNew(ContainerContext ctx) {
            TickContext tick = new TickContext();
            tick.reset(ctx);
            return tick;
        }
    }
}