package com.qiqi.li.living.api;

import java.util.List;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

/**
 * 容器级数据驱动接口。
 *
 * <p>标记某功能需要在「容器级数据流程」中运行（而非仅 per-slot 的 {@code tick}）。框架按其
 * {@link #getPriority} 决定执行顺序，每 tick 调用 {@link #tickContainerData} 计算 / 维护容器级状态
 * （如红石信号层、流体层、水车应力），可绕过「本容器有无该物品」的限制自维持驱动。</p>
 */
public interface HasContainerData {

    int getPriority();

    void tickContainerData(List<LivingItemFunction.SlotEntry> entries, ContainerContext ctx, TickContext tick);
}