package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.neoforged.neoforge.attachment.AttachmentType;

/**
 * 容器级数据的类型化 key。
 *
 * <p>每个 key 携带一个全局递增的 {@code index}，{@link ContainerDataStore} 用它做
 * <b>数组下标</b>而非哈希查找 ⇒ 访问成本接近普通字段读（~1ns），不给热路径加负担。</p>
 *
 * <h3>两种维度</h3>
 * <ul>
 *   <li><b>层级</b> —— {@link #of(String, Supplier)} 是 <b>tick 级</b>（每次 tick 重建，
 *       存 {@code TickContext}）；{@link #persistent(String, Supplier)} 与
 *       {@link #persistentWith(String, Supplier, Supplier)} 是 <b>容器级</b>
 *       （跨 tick 持久，存 {@code ContainerEntry}）。</li>
 *   <li><b>落盘</b> —— 仅 {@link #persistentWith} 声明了 BE attachment，写回时按它落盘；
 *       其余 key 不参与 attachment 写回（红石 / 电力靠相位快照等专用机制落盘）。</li>
 * </ul>
 *
 * <p>key 是静态常量、随类加载自动登记到 {@link #all()}，<b>无需注册步骤</b>。</p>
 */
public final class ContainerDataKey<T> {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final List<ContainerDataKey<?>> ALL = new ArrayList<>();

    private final int index = SEQ.getAndIncrement();
    private final String id;
    private final Supplier<T> factory;
    private final boolean persistent;
    private final Supplier<AttachmentType<T>> attachment;   // null ⇒ 不参与 attachment 写回

    private ContainerDataKey(String id, Supplier<T> factory, boolean persistent, Supplier<AttachmentType<T>> attachment) {
        this.id = id;
        this.factory = factory;
        this.persistent = persistent;
        this.attachment = attachment;
        ALL.add(this);
    }

    /** tick 级 key：数据只活一个 tick，存 {@code TickContext}。 */
    public static <T> ContainerDataKey<T> of(String id, Supplier<T> factory) {
        return new ContainerDataKey<>(id, factory, false, null);
    }

    /** 容器级 key：数据跨 tick 持久，但不参与 attachment 写回。 */
    public static <T> ContainerDataKey<T> persistent(String id, Supplier<T> factory) {
        return new ContainerDataKey<>(id, factory, true, null);
    }

    /**
     * 容器级 key：数据跨 tick 持久，且落盘到声明的 BE attachment。
     *
     * <p>⚠️ attachment 用 {@link Supplier} <b>延迟求值</b>（2026-10-08，计划 ⑤）——
     * key 现由各领域数据类在<b>类加载时</b>定义，而 {@code DeferredHolder.value()}
     * 必须等注册完成后才可读（提前读抛「unbound value」）。</p>
     */
    public static <T> ContainerDataKey<T> persistentWith(String id, Supplier<T> factory,
                                                         Supplier<AttachmentType<T>> attachment) {
        return new ContainerDataKey<>(id, factory, true, attachment);
    }

    /** 数组下标（由 {@link ContainerDataStore} 使用）。 */
    public int index() { return index; }

    public String id() { return id; }

    /** 创建一个该类型的新实例（惰性创建时由 store 调用）。 */
    public T create() { return factory.get(); }

    /** 是否容器级（跨 tick 持久）。 */
    public boolean isPersistent() { return persistent; }

    /** 落盘目标；仅容器级且声明了 attachment 的 key 有值（延迟求值，须在注册后调用）。 */
    public AttachmentType<T> attachment() { return attachment != null ? attachment.get() : null; }

    /** 所有已定义的 key（类加载顺序）。供写回遍历使用。 */
    public static List<ContainerDataKey<?>> all() {
        return Collections.unmodifiableList(ALL);
    }

    @Override
    public String toString() {
        return "ContainerDataKey(" + id + (persistent ? ", persistent" : ", tick") + ")";
    }
}
