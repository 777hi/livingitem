package com.qiqi.li.living.api;

/**
 * 容器级数据的「生命周期」接口 —— 供框架做<b>过期清理</b>。
 *
 * <p>框架按「容器条目内<b>所有</b>数据类型是否都过期（超过 120s 未被 tick）」来决定回收整条缓存。
 * 原先这个判据硬编码在 {@code ContainerLivingItemHandler.cleanupStaleData} 里，直接读
 * {@code ContainerFluidData} / {@code ContainerRedstoneData} / {@code ContainerPowerData}
 * 的 {@code getLastTickTime()} ⇒ container 包被迫认识这些领域类（分层违规）。</p>
 *
 * <p>改为接口后，框架遍历已登记的 {@code ContainerDataKey}、按接口询问，不再认识任何具体领域类型。
 * 实现者：{@code ContainerFluidData} / {@code ContainerRedstoneData} / {@code ContainerPowerData}
 * （三者均跨 tick 持久、需要过期回收；tick 级的 {@code ContainerStressData} 不实现）。</p>
 */
public interface ContainerDataLifecycle {

    /** 最后一次被 tick 的毫秒时间戳（{@code System.currentTimeMillis()} 口径）。 */
    long getLastTickTime();
}
