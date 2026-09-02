package com.qiqi.li.living.container;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;

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
 * <p>由 {@link ContainerLivingItemHandler} 在每次 tick 开始时创建，tick 结束后自然丢弃。
 * 生命周期短、对象小，JVM 年轻代 GC 可高效回收，无需池化。</p>
 */
public class TickContext {

    public final Set<String> occupiedSlots = new HashSet<>();
    public final Set<Integer> transferredTargetSlots = new HashSet<>();
    public final Set<Integer> dirtySlots = new HashSet<>();
    public ContainerFluidData fluidData = ContainerFluidData.EMPTY;
    public ContainerStressData stressData = ContainerStressData.EMPTY;
    public ContainerRedstoneData redstoneData = null;
    public com.qiqi.li.living.domain.power.ContainerPowerData powerData = null;

    private Map<String, Set<Integer>> functionSlots = Collections.emptyMap();

    private final ContainerContext ctx;
    private ContainerSnapshot _snapshot = ContainerSnapshot.EMPTY;
    private boolean snapshotBuilt = false;

    public TickContext(ContainerContext ctx) {
        this.ctx = ctx;

        ContainerFluidData fluidData = ContainerFluidData.EMPTY;
        if (ctx instanceof SimpleContainerContext simpleCtx) {
            ContainerFluidData fetched = simpleCtx.getOrCreateFluidData();
            if (fetched != null) {
                fluidData = fetched;
            }
        }
        this.fluidData = fluidData;
        this.stressData = new ContainerStressData();
    }

    /**
     * 获取容器快照（延迟构建 + 跨 tick 缓存）。
     * 仅当容器修订计数相对上次构建变化时才重建，否则复用缓存的同一快照。
     */
    public ContainerSnapshot getSnapshot() {
        if (!snapshotBuilt) {
            long rev = ContainerLivingItemHandler.getContainerRevision(ctx);
            _snapshot = ContainerLivingItemHandler.getCachedSnapshot(ctx, rev, fluidData, this);
            snapshotBuilt = true;
        }
        return _snapshot;
    }

    /**
     * 获取或创建容器红石数据。
     * 优先从持久化的 {@link SimpleContainerContext} 获取，确保 edgeGrid 跨 tick 保持。
     */
    public ContainerRedstoneData getOrCreateRedstoneData(ContainerContext context) {
        if (redstoneData == null) {
            if (context instanceof SimpleContainerContext simpleCtx) {
                redstoneData = simpleCtx.getOrCreateRedstoneData();
            } else {
                redstoneData = new ContainerRedstoneData();
            }
        }
        return redstoneData;
    }

    /**
     * 获取或创建容器红电数据（电力层账本）。
     * 优先从持久化的 {@link SimpleContainerContext} 获取，确保事件状态跨 tick 保持。
     */
    public com.qiqi.li.living.domain.power.ContainerPowerData getOrCreatePowerData(ContainerContext context) {
        if (powerData == null) {
            if (context instanceof SimpleContainerContext simpleCtx) {
                powerData = simpleCtx.getOrCreatePowerData();
            } else {
                powerData = new com.qiqi.li.living.domain.power.ContainerPowerData();
            }
        }
        return powerData;
    }

    /**
     * 获取指定功能的活跃槽位集合（只读）。
     * 由 {@link ContainerLivingItemHandler} 在分组后填充，功能类可直接读取，
     * 无需再遍历整个容器查找其他功能的槽位。
     *
     * @param functionId 功能 ID，如 "living_hopper"、"living_ender_chest"
     * @return 活跃槽位集合（不可修改），不存在则返回空集合
     */
    public Set<Integer> getFunctionSlots(String functionId) {
        Set<Integer> slots = functionSlots.get(functionId);
        return slots != null ? Collections.unmodifiableSet(slots) : Collections.emptySet();
    }

    /**
     * 设置功能槽位缓存（由 processContext 调用）。
     */
    public void setFunctionSlots(Map<String, Set<Integer>> functionSlots) {
        this.functionSlots = functionSlots;
    }
}