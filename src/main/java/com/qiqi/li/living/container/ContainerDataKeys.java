package com.qiqi.li.living.container;

import com.qiqi.li.living.domain.power.ContainerPowerData;
import com.qiqi.li.living.domain.redstone.ContainerRedstoneData;
import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;
import com.qiqi.li.living.transfer.LivingComponents;

/**
 * 全部容器级数据的 key 清单。
 *
 * <p>⚠️ 目前是<b>集中定义</b>（新增一种数据要在此加一行）——相比旧架构
 * 「改 {@code ContainerEntry} / {@code SimpleContainerContext} / {@code TickContext}
 * 三个类」已经是 3 → 1。「领域各自定义 key」是后续可选迁移，届时本类删除、
 * 各 key 挪到对应的数据类里即可，机制不变。</p>
 */
public final class ContainerDataKeys {

    /** 容器流体状态（跨 tick 持久 + BE attachment 落盘）。 */
    public static final ContainerDataKey<ContainerFluidData> FLUID =
        ContainerDataKey.persistentWith("fluid", ContainerFluidData::new,
            LivingComponents.CONTAINER_FLUID_DATA.value());

    /** 容器红石账本（跨 tick 持久；不落盘 attachment）。 */
    public static final ContainerDataKey<ContainerRedstoneData> REDSTONE =
        ContainerDataKey.persistent("redstone", ContainerRedstoneData::new);

    /** 容器红电账本（跨 tick 持久；不落盘 attachment —— 相位快照走专用机制）。 */
    public static final ContainerDataKey<ContainerPowerData> POWER =
        ContainerDataKey.persistent("power", ContainerPowerData::new);

    /** 容器应力（tick 级：每 tick 重建，tick 末写回 BE 供 Create 读取）。 */
    public static final ContainerDataKey<ContainerStressData> STRESS =
        ContainerDataKey.of("stress", ContainerStressData::new);

    private ContainerDataKeys() {}
}
