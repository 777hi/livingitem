package com.qiqi.li.living.runtime;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一个「运行时数据片段」的类型 —— 全局唯一 id + 自描述编解码。
 *
 * <h3>它解决什么</h3>
 * <p>活物品的运行时数据（tooltip 用的遥测 / 瞬态字段）按**领域**分成若干段：发电机、漏斗、熔炉…
 * 本机制把「有哪些段」从<b>框架硬编码</b>改为<b>各领域自登记</b>：</p>
 * <pre>
 *   旧：LivingItemRuntimeData 硬编码 3 个字段 ⇒ runtime 必须认识 hopper / furnace / power（R3 违规）
 *       同步包用单字节 flags + 3 个 if 分支 ⇒ 新增一种遥测要改网络包
 *   新：领域各自实现本接口并在 {@link RuntimeSegmentRegistry} 登记
 *       ⇒ runtime 不认识任何领域，同步包也不认识任何具体字段
 * </pre>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li><b>{@link #id()} 是线格式契约</b>：它进网络包，<b>一旦发布就不能改名</b>
 *       （改了老客户端解不出）。命名用领域语义（{@code "generator"} / {@code "hopper"} / {@code "furnace"}）。</li>
 *   <li>实现类建议做成单例（{@code public static final XxxSegment INSTANCE}）。</li>
 *   <li>值类型应<b>不可变</b>（record 优先）—— 它跨线程传递且被快照。</li>
 *   <li>{@link #codec()} 用 {@link RegistryFriendlyByteBuf} —— 与项目既有网络包口径一致
 *       （部分段需要 registry access，如物品 / 组件）。</li>
 * </ul>
 *
 * @param <T> 该片段的载荷类型
 */
public interface RuntimeSegmentType<T> {

    /** 全局唯一且**稳定的** id（进网络包，发布后不可改）。 */
    String id();

    /** 该片段的自描述编解码。 */
    StreamCodec<? super RegistryFriendlyByteBuf, T> codec();
}
