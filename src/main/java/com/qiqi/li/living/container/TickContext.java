package com.qiqi.li.living.container;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.qiqi.li.living.domain.power.ContainerPowerData;
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

    /**
     * tick 级数据的统一存储（1a-4）。
     * 容器级持久 key 的数据存在 {@code ContainerEntry.store}（跨 tick），
     * 这里只放 tick 级的（如应力）；读取统一走 {@link #data(ContainerDataKey)}。
     */
    private final ContainerDataStore tickData = new ContainerDataStore();

    private Map<String, Set<Integer>> functionSlots = Collections.emptyMap();

    private final ContainerContext ctx;
    private ContainerSnapshot _snapshot = ContainerSnapshot.EMPTY;
    private boolean snapshotBuilt = false;

    public TickContext(ContainerContext ctx) {
        this.ctx = ctx;
        // 应力是 tick 级新建数据：每 tick 一个新实例，tick 末写回 BE（供 Create 读取）
        tickData.put(ContainerStressData.KEY, new ContainerStressData());
        // ⚠️ 必须在此创建容器的流体数据 —— 1a-4「容器级数据统一存储」曾漏掉此调用
        // （旧版由 getOrCreateFluidData() 在此创建），导致 tick.fluidData() 恒为 EMPTY
        // ⇒ 活水桶的 registerSource 被跳过 ⇒ 水流整体失效。
        // 与 1a-4 之前一致：仅对 SimpleContainerContext 创建，且含 BE 附件回填（LRU 驱逐后恢复）。
        if (ctx instanceof SimpleContainerContext) {
            ContainerLivingItemHandler.getFluidData(ctx);
        }
    }

    /**
     * 读容器级 / tick 级数据（1a-4 的统一入口）。
     * 持久 key 委托给容器的 store；tick 级 key 查本 tick 的 store。
     */
    public <T> T data(ContainerDataKey<T> key) {
        return key.isPersistent() ? ctx.peekContainerData(key) : tickData.peek(key);
    }

    /** 写 tick 级数据（持久 key 请走 {@code ctx.getOrCreateContainerData}）。 */
    public <T> void put(ContainerDataKey<T> key, T value) {
        tickData.put(key, value);
    }

    /** 本容器的流体数据（保证非 null：无数据时返回 {@link ContainerFluidData#EMPTY}）。 */
    public ContainerFluidData fluidData() {
        ContainerFluidData f = data(ContainerFluidData.KEY);
        return f != null ? f : ContainerFluidData.EMPTY;
    }

    /** 本 tick 的应力数据（构造时新建，保证非 null）。 */
    public ContainerStressData stressData() {
        ContainerStressData s = data(ContainerStressData.KEY);
        return s != null ? s : new ContainerStressData();
    }

    /** 本容器的红电账本（可能为 null：容器不支持时）。 */
    public ContainerPowerData powerData() {
        return data(ContainerPowerData.KEY);
    }

    /**
     * 获取容器快照（延迟构建 + 跨 tick 缓存）。
     * 仅当容器修订计数相对上次构建变化时才重建，否则复用缓存的同一快照。
     */
    public ContainerSnapshot getSnapshot() {
        if (!snapshotBuilt) {
            long rev = ContainerLivingItemHandler.getContainerRevision(ctx);
            _snapshot = ContainerLivingItemHandler.getCachedSnapshot(ctx, rev, fluidData(), this);
            snapshotBuilt = true;
        }
        return _snapshot;
    }

    /**
     * 获取或创建容器红石数据。
     * 统一走容器的持久 store（1a-4），确保 edgeGrid 跨 tick 保持。
     */
    public ContainerRedstoneData getOrCreateRedstoneData(ContainerContext context) {
        return context.getOrCreateContainerData(ContainerRedstoneData.KEY);
    }

    /**
     * 获取感知端口（v19.1 架构演进 ②）：电力层与跨层消费者读取信号层的唯一接口。
     * 依赖收窄到接口——edgeGrid 的边模型后续重构只改端口实现。
     *
     * <p>⚠️ 接口**已上移到契约层** `living/api/`（2026-10-08 C 收尾）——
     * 否则消费者用端口仍要 import `domain/redstone`，模块级依赖并未真正切断。</p>
     */
    public com.qiqi.li.living.api.RedstoneSensor getSensor(ContainerContext context) {
        return getOrCreateRedstoneData(context);
    }

    /**
     * 获取或创建容器红电数据（电力层账本）。
     * 统一走容器的持久 store（1a-4），确保事件状态跨 tick 保持。
     */
    public ContainerPowerData getOrCreatePowerData(ContainerContext context) {
        return context.getOrCreateContainerData(ContainerPowerData.KEY);
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