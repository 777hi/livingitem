package com.qiqi.li.living.container;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.qiqi.li.living.api.FluidPresence;
import com.qiqi.li.living.api.RedstoneSensor;

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

    /**
     * 感知端口解析器（由红石领域在自己的 Registration 里注册）。
     * 让本类只认契约层 {@link RedstoneSensor}，不认识具体的红石账本实现。
     */
    private static volatile Function<ContainerContext, RedstoneSensor> sensorResolver = c -> null;

    /** 流体存在性视图解析器（由水领域注册）—— 本类只认契约层 {@link FluidPresence}。 */
    private static volatile Function<TickContext, FluidPresence> fluidPresenceResolver = t -> null;

    /** 注册感知端口解析器（红石领域调用）。 */
    public static void registerSensorResolver(Function<ContainerContext, RedstoneSensor> resolver) {
        sensorResolver = resolver;
    }

    /** 注册流体存在性视图解析器（水领域调用）。 */
    public static void registerFluidPresenceResolver(Function<TickContext, FluidPresence> resolver) {
        fluidPresenceResolver = resolver;
    }

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
        // ⚠️ 容器级数据的「首次创建 + 附件回填」（如流体数据）由各领域注册的
        // ContainerTickHook#onTickStart 负责 —— 它在紧随本构造的 setTickContext 里触发，
        // 时机与原「在此调 getFluidData」等价（2026-10-08 计划 ⑤ 移出）。
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

    /**
     * 读或创建容器级 / tick 级数据（1a-4 的统一入口，带创建语义）。
     *
     * <p>持久 key 委托给容器的 store；tick 级 key 查本 tick 的 store，缺失时用 key 的工厂创建。</p>
     */
    public <T> T getOrCreateData(ContainerDataKey<T> key) {
        if (key.isPersistent()) {
            return ctx.getOrCreateContainerData(key);
        }
        T v = tickData.peek(key);
        if (v == null) {
            v = key.create();
            tickData.put(key, v);
        }
        return v;
    }

    /**
     * 获取容器快照（延迟构建 + 跨 tick 缓存）。
     * 仅当容器修订计数相对上次构建变化时才重建，否则复用缓存的同一快照。
     */
    public ContainerSnapshot getSnapshot() {
        if (!snapshotBuilt) {
            long rev = ContainerLivingItemHandler.getContainerRevision(ctx);
            _snapshot = ContainerLivingItemHandler.getCachedSnapshot(ctx, rev, this);
            snapshotBuilt = true;
        }
        return _snapshot;
    }

    /**
     * 获取感知端口（v19.1 架构演进 ②）：电力层与跨层消费者读取信号层的唯一接口。
     * 依赖收窄到接口——edgeGrid 的边模型后续重构只改端口实现。
     *
     * <p>⚠️ 接口**已上移到契约层** `living/api/`（2026-10-08 C 收尾）——
     * 否则消费者用端口仍要 import `domain/redstone`，模块级依赖并未真正切断。</p>
     *
     * <p>实现由红石领域<b>注册</b>（2026-10-08 计划 ⑤）—— 本类只认契约层
     * {@link RedstoneSensor}，不认识具体的红石账本实现。</p>
     */
    public RedstoneSensor getSensor(ContainerContext context) {
        return sensorResolver.apply(context);
    }

    /**
     * 获取流体「存在性」视图（供<b>跨领域</b>消费者，如活耕地判湿）。
     *
     * <p>返回契约层 {@link FluidPresence} 而非具体的流体数据实现 ⇒
     * 消费者不必 import 水领域。实现由水领域注册。</p>
     */
    public FluidPresence getFluidPresence() {
        return fluidPresenceResolver.apply(this);
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