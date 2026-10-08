package com.qiqi.li.living.api;

/**
 * 流体「存在性」只读视图 —— 供<b>跨领域</b>消费者（如活耕地判湿）查询流体，而不必 import 水领域。
 *
 * <p>背景（2026-10-08 计划 ⑤）：{@code TickContext.fluidData()} 原返回具体的
 * {@code ContainerFluidData}，使 container 包被迫认识水领域。而真正跨领域的使用者（活耕地）
 * 只需要「某槽位有没有流体」这一个判据 ⇒ 抽成本接口后，中继方（{@code TickContext}）
 * 与消费方都只依赖契约层。</p>
 *
 * <p>实现者：{@code ContainerFluidData}（水领域）。</p>
 */
public interface FluidPresence {

    /** 该容器当前是否完全没有流体（廉价短路判据）。 */
    boolean isEmpty();

    /** 指定槽位是否存在流体（含源与流动）。 */
    boolean hasFluidAt(int slot);
}
