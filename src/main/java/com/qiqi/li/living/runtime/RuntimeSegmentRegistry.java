package com.qiqi.li.living.runtime;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 运行时数据片段类型注册表 —— id ⇄ {@link RuntimeSegmentType} 的唯一映射。
 *
 * <h3>归属（为什么它在 L2 框架层）</h3>
 * <p>本类<b>不认识任何领域</b>：只提供「登记 / 按 id 查」两件事。
 * 各领域在自己的 {@code XxxRegistration} 里登记自己的片段 —— 这正是项目既有的
 * <b>「注册表 vs 被注册者」</b>模式（同 {@code LivingComponents} / {@code StaticCacheRegistry}）。</p>
 *
 * <p>⇒ 依赖方向恒为 {@code domain → runtime}（L3 → L2，合规）。</p>
 *
 * <h3>⚠️ 单测红线</h3>
 * <p>Gradle 在**同一 JVM** 跑全部测试 ⇒ 静态注册表会<b>跨测试类泄漏</b>。
 * 测试用例必须在 {@code @BeforeEach} 调 {@link #clearForTest()}（项目铁律）。</p>
 */
public final class RuntimeSegmentRegistry {

    private static final Map<String, RuntimeSegmentType<?>> BY_ID = new LinkedHashMap<>();

    private RuntimeSegmentRegistry() {}

    /**
     * 登记一个片段类型。重复 id 会抛异常（提前暴露复制粘贴错误）。
     *
     * @throws IllegalStateException id 已被登记
     */
    public static synchronized void register(RuntimeSegmentType<?> type) {
        RuntimeSegmentType<?> prev = BY_ID.putIfAbsent(type.id(), type);
        if (prev != null && prev != type) {
            throw new IllegalStateException(
                "重复的 runtime 片段 id: " + type.id()
                    + "（已有 " + prev.getClass().getName() + "，新来 " + type.getClass().getName() + "）");
        }
    }

    /** 按 id 查片段类型（未登记返回 {@code null}）。 */
    @Nullable
    public static synchronized RuntimeSegmentType<?> byId(String id) {
        return BY_ID.get(id);
    }

    /** 已登记的全部 id（插入顺序）。 */
    public static synchronized Set<String> ids() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(BY_ID.keySet()));
    }

    /** 已登记的数量（诊断用）。 */
    public static synchronized int size() {
        return BY_ID.size();
    }

    /**
     * 清空注册表 —— **仅供测试**（见类注释的单测红线）。
     *
     * <p>⚠️ 生产代码不得调用：清掉之后网络包解不出任何片段。</p>
     */
    public static synchronized void clearForTest() {
        BY_ID.clear();
    }
}
