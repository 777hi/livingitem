package com.qiqi.li.living.container;

/**
 * 容器 tick 生命周期钩子 —— 由各领域注册，在容器 tick 的固定阶段被调用。
 *
 * <p>设计动机（2026-10-08 计划 ⑤）：{@code container} 包原先硬编码了几段<b>领域特定</b>的
 * tick 逻辑（红石账本 reset、流体/应力/相位快照写回），使容器内核被迫 import 各领域类。
 * 改为注册驱动后，container 只认本接口，领域在自己的 {@code *Registration} 里注册实现 ——
 * 与 {@link SnapshotProvider} 同一套思路。</p>
 *
 * <p>两个阶段均为 {@code default} 空实现 ⇒ 领域只需覆写自己关心的那个。</p>
 */
public interface ContainerTickHook {

    /**
     * tick 开始（{@code TickContext} 已就绪、尚未进入功能 tick）。
     *
     * <p>用于领域做「本 tick 的初始化」，例如红石领域在此把账本的 {@code processedThisTick}
     * 归位（时机与原硬编码在 {@code SimpleContainerContext#setTickContext} 里完全一致）。</p>
     */
    default void onTickStart(ContainerContext ctx, TickContext tick) {
    }

    /**
     * 写回阶段（容器级数据算完之后）—— 把容器级数据落盘到 BE / Player attachment。
     *
     * <p>调用方保证 ctx 是 {@link TickableContainerContext}。</p>
     */
    default void onWriteback(TickableContainerContext ctx, TickContext tick) {
    }
}
