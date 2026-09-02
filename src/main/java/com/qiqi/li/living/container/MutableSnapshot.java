package com.qiqi.li.living.container;

import java.util.Arrays;
import java.util.Set;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.transfer.FilterData;

/**
 * 快照构造器 —— 跨 domain 共享的可变中间体。
 *
 * <p>各 {@link SnapshotProvider} 在 {@link ContainerSnapshot#capture} 期间依次写入不同字段，
 * 最后由 {@link #build} 固化为不可变的 {@link ContainerSnapshot}。</p>
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@code sourceOf/targetOf}：漏斗源/目标槽位（-1 表示无）；</li>
 *   <li>{@code filterOf}：漏斗过滤数据；</li>
 *   <li>{@code chestOf}：活箱子占用快照；</li>
 *   <li>{@code redstoneMaskOf}：红电元件类型位图（见 domain.redstone 的 BIT_*）；</li>
 *   <li>{@code capOf}：传导能力（红电铜氧化等级，-1 表示非铜）。</li>
 * </ul>
 */
public class MutableSnapshot {

    final int size;
    public final int[] sourceOf;
    public final int[] targetOf;
    public FilterData[] filterOf = new FilterData[0];
    public final ContainerSnapshot.ChestSnapshot[] chestOf;
    final int[] redstoneMaskOf;
    final int[] capOf;

    MutableSnapshot(int size) {
        this.size = size;
        this.sourceOf = new int[size];
        this.targetOf = new int[size];
        Arrays.fill(sourceOf, -1);
        Arrays.fill(targetOf, -1);
        this.chestOf = new ContainerSnapshot.ChestSnapshot[size];
        this.redstoneMaskOf = new int[size];
        this.capOf = new int[size];
        Arrays.fill(capOf, -1);
    }

    /** 在红电位图上为给定槽位集合打上某一位（多个贡献者可叠加） */
    public void markRedstone(Set<Integer> slots, int bit) {
        if (slots == null) return;
        for (int slot : slots) {
            if (slot >= 0 && slot < size) redstoneMaskOf[slot] |= bit;
        }
    }

    /** 设置某槽位的传导能力值（如铜氧化等级） */
    public void capOf(int slot, int level) {
        if (slot >= 0 && slot < size) capOf[slot] = level;
    }

    ContainerSnapshot build(ContainerFluidData fluidData) {
        return new ContainerSnapshot(size, sourceOf, targetOf, filterOf, chestOf,
            redstoneMaskOf, capOf, fluidData);
    }
}
