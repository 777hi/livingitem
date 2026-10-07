package com.qiqi.li.living.runtime;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次快照的「片段集合」—— 一个槽位的运行时数据，按片段 id 索引。
 *
 * <p>取代原先硬编码三个字段的聚合 record：本类<b>不认识任何具体片段</b>，
 * 只提供按 {@link RuntimeSegmentType} 收发的类型安全外壳。</p>
 *
 * <h3>为什么 {@code get} 收 {@link RuntimeSegmentType} 而不是 {@code String}</h3>
 * <p>{@code public <T> T get(RuntimeSegmentType<T> type)} 让<b>编译期</b>就能校验
 * 「拿到的类型对不对」—— 传错片段类型直接编译不过，不需要运行时强转。
 * （若收 {@code String}，调用方得自己 {@code (FurnaceRuntime) data.get("furnace")}，
 * 拼错 id 只在运行时炸。）</p>
 *
 * <p>本类<b>不可变</b>：{@link #with} 返回新实例。空实例用 {@link #EMPTY}。</p>
 */
public final class RuntimeSegments {

    /** 无任何片段（槽位没有运行时数据）。 */
    public static final RuntimeSegments EMPTY = new RuntimeSegments(Map.of());

    private final Map<String, Object> byId;

    private RuntimeSegments(Map<String, Object> byId) {
        this.byId = byId;
    }

    /**
     * 附加（或替换）一个片段，返回新实例。
     *
     * @param type  片段类型（提供 id）
     * @param value 载荷（非 null）
     */
    public <T> RuntimeSegments with(RuntimeSegmentType<T> type, T value) {
        if (value == null) {
            throw new IllegalArgumentException("runtime 片段值不可为 null: " + type.id());
        }
        Map<String, Object> next = new LinkedHashMap<>(byId);
        next.put(type.id(), value);
        return new RuntimeSegments(Collections.unmodifiableMap(next));
    }

    /**
     * 取一个片段的载荷；未包含该片段返回 {@code null}。
     *
     * <p>泛型由 {@code type} 推导 ⇒ 无需强转。调用方应把 {@code null} 当作
     * 「本槽没有该段数据，请回退到 DataComponent 旧值」（与改造前的
     * {@code isXxx() == false} 语义一致）。</p>
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(RuntimeSegmentType<T> type) {
        return (T) byId.get(type.id());
    }

    /** 本槽是否没有任何片段。 */
    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** 片段数量。 */
    public int size() {
        return byId.size();
    }

    /**
     * 按 id 取原始载荷 —— **仅供网络包编解码**（编解码时手里只有 id 字符串，
     * 且它需要把值交给该 id 登记的 codec）。业务代码请用 {@link #get(RuntimeSegmentType)}。
     */
    @Nullable
    public Object rawById(String id) {
        return byId.get(id);
    }

    /** 已包含的片段 id 集合（插入顺序，不可变）—— **仅供网络包编解码**。 */
    public java.util.Set<String> ids() {
        return byId.keySet();
    }
}
