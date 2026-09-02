package com.qiqi.li.living.container;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.transfer.FilterData;

/**
 * 容器快照 —— 各 domain 关心的槽位级信息的只读聚合（SoA 结构）。
 *
 * <p>改为<b>注册驱动</b>：本类不再 import 任何 domain 逻辑类（原先直接依赖
 * {@code domain.hopper}/{@code domain.chest}，构成分层违规），而是遍历已注册的
 * {@link SnapshotProvider} 收集数据。每个 domain 在自己的包内实现贡献逻辑并向本类注册。
 * 这样既解耦，又让所有 domain 共享"编译一次、跨 tick 缓存"的快照（缓存见
 * {@link ContainerLivingItemHandler}）。</p>
 */
public class ContainerSnapshot {

    public record ChestSnapshot(int usedSlots, int usedBytes, boolean isFull, boolean isByteFull) {
        public static final ChestSnapshot EMPTY = new ChestSnapshot(0, 0, false, false);
    }

    private static final List<SnapshotProvider> PROVIDERS = new ArrayList<>();

    /** 注册快照贡献者。按类去重，避免 mod 重复初始化时叠加多份。 */
    public static void registerProvider(SnapshotProvider provider) {
        for (SnapshotProvider p : PROVIDERS) {
            if (p.getClass() == provider.getClass()) return;
        }
        PROVIDERS.add(provider);
    }

    public static final ContainerSnapshot EMPTY = new ContainerSnapshot(0, new int[0], new int[0],
        new FilterData[0], new ChestSnapshot[0], new int[0], new int[0], ContainerFluidData.EMPTY);

    private final int containerSize;
    private final int[] sourceOf;
    private final int[] targetOf;
    private final FilterData[] filterOf;
    private final ChestSnapshot[] chestOf;
    private final int[] redstoneMaskOf;
    private final int[] capOf;
    private final ContainerFluidData fluidData;

    ContainerSnapshot(int containerSize, int[] sourceOf, int[] targetOf,
                      FilterData[] filterOf, ChestSnapshot[] chestOf,
                      int[] redstoneMaskOf, int[] capOf, ContainerFluidData fluidData) {
        this.containerSize = containerSize;
        this.sourceOf = sourceOf;
        this.targetOf = targetOf;
        this.filterOf = filterOf;
        this.chestOf = chestOf;
        this.redstoneMaskOf = redstoneMaskOf;
        this.capOf = capOf;
        this.fluidData = fluidData;
    }

    /**
     * 捕获容器快照。遍历所有已注册贡献者填入各自字段。
     * 红电位图与 capOf 由红电贡献者填充；漏斗/箱子贡献者填充各自字段。
     */
    public static ContainerSnapshot capture(ContainerContext context, TickContext tick, ContainerFluidData fluidData) {
        int containerSize = context.getSize();
        int containerWidth = context.getWidth();
        MutableSnapshot builder = new MutableSnapshot(containerSize);
        for (SnapshotProvider provider : PROVIDERS) {
            provider.contribute(context, tick, containerSize, containerWidth, builder);
        }
        return builder.build(fluidData);
    }

    public int getContainerSize() {
        return containerSize;
    }

    public int[] getSourceOf() {
        return sourceOf;
    }

    public int[] getTargetOf() {
        return targetOf;
    }

    public FilterData[] getFilterOf() {
        return filterOf;
    }

    public int getSourceOf(int slot) {
        return slot >= 0 && slot < containerSize ? sourceOf[slot] : -1;
    }

    public int getTargetOf(int slot) {
        return slot >= 0 && slot < containerSize ? targetOf[slot] : -1;
    }

    public FilterData getFilterOf(int slot) {
        // 用 filterOf.length 而非 containerSize 守卫：MutableSnapshot.filterOf 默认长度为 0，
        // 若某 SnapshotProvider 未重新赋值，越界访问会抛 AIOOBE。无过滤数据时回退 EMPTY 更稳健。
        return slot >= 0 && slot < filterOf.length ? filterOf[slot] : FilterData.EMPTY;
    }

    public ContainerFluidData getFluidData() {
        return fluidData;
    }

    public ChestSnapshot getChestSnapshot(int slot) {
        return slot >= 0 && slot < containerSize && chestOf[slot] != null
            ? chestOf[slot] : ChestSnapshot.EMPTY;
    }

    public ChestSnapshot[] getChestSnapshots() {
        return chestOf;
    }

    /** 红电元件类型位图（某槽位属于哪些红电元件的按位或）。无红电贡献者时为全 0。 */
    public int getRedstoneMaskOf(int slot) {
        return slot >= 0 && slot < containerSize ? redstoneMaskOf[slot] : 0;
    }

    public int[] getRedstoneMaskOf() {
        return redstoneMaskOf;
    }

    /** 传导能力值（红电：铜氧化等级；非铜槽位为 -1）。 */
    public int getCapOf(int slot) {
        return slot >= 0 && slot < containerSize ? capOf[slot] : -1;
    }

    public int[] getCapOf() {
        return capOf;
    }
}
