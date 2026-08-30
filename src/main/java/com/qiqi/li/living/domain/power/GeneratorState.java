package com.qiqi.li.living.domain.power;

import java.util.ArrayList;
import java.util.List;

/**
 * 单台发电机的发电状态（按槽位索引）。
 *
 * <p>形态 = 线圈分组（§3.4 感应拓扑）：
 * 铜块/格栅 1 组×4 向（全叠加）、雕文 2 组（V/H 隔离）、切制 1 组×1 向。
 * 每个方向贡献 2 条路径：直连（邻居边信号）+ 感应（该方向邻居发电机的耦合转发）。
 * 形状/方向变化时重建通道（波形状态重置，重新起振）。</p>
 */
public class GeneratorState {

    /** 偏好周期 = 堆叠数（§3.4 因子二）；0 表示未设置（<2 即宽带态） */
    private int preferredPeriod;

    private final List<ChannelState> channels = new ArrayList<>();

    /** 方向 → 通道索引（-1 = 该方向不感知） */
    private final int[] dirChannel = {-1, -1, -1, -1};
    /** 方向 → 通道内直连路索引 */
    private final int[] dirDirectPath = new int[4];
    /** 方向 → 通道内感应路索引（来自该方向的邻居发电机耦合转发） */
    private final int[] dirVirtualPath = new int[4];

    /** 线圈配置指纹（变化时才重建，避免每 tick 重置波形状态） */
    private int coilConfigKey = Integer.MIN_VALUE;

    public GeneratorState() {
        // 默认全向单通道（铜块/格栅），glue 每 tick 用 configureCoilsIfChanged 校正
        configureCoilsIfChanged(10_000, new int[][]{{0, 1, 2, 3}});
    }

    /**
     * 线圈配置变更时重建通道。
     *
     * @param groups 每个线圈的边方向集合（0=UP 1=DOWN 2=LEFT 3=RIGHT）
     */
    public void configureCoilsIfChanged(int configKey, int[][] groups) {
        if (this.coilConfigKey == configKey) return;
        this.coilConfigKey = configKey;

        channels.clear();
        // 全部方向映射重置（防形态切换后的旧下标残留越界）
        for (int d = 0; d < 4; d++) {
            dirChannel[d] = -1;
            dirDirectPath[d] = 0;
            dirVirtualPath[d] = 0;
        }

        for (int c = 0; c < groups.length; c++) {
            ChannelState channel = new ChannelState();
            for (int d : groups[c]) {
                dirChannel[d] = c;
                dirDirectPath[d] = channel.pathCount();
                channel.addPath();                       // 直连路
                dirVirtualPath[d] = channel.pathCount();
                channel.addPath();                       // 感应路（该方向邻居发电机）
            }
            channels.add(channel);
        }
    }

    public int channelCount() {
        return channels.size();
    }

    public ChannelState channel(int index) {
        return channels.get(index);
    }

    /** v1 便捷入口：主通道（全向形态即唯一通道；多通道形态慎用，阶段三按 dirChannel 路由） */
    public ChannelState primaryChannel() {
        return channels.get(0);
    }

    /** 方向 → 通道索引（-1 = 不感知） */
    public int dirChannel(int dir) {
        return dirChannel[dir];
    }

    /** 方向 → 通道内直连路索引 */
    public int dirDirectPath(int dir) {
        return dirDirectPath[dir];
    }

    /** 方向 → 通道内感应路索引（来自该方向邻居发电机的耦合转发） */
    public int dirVirtualPath(int dir) {
        return dirVirtualPath[dir];
    }

    public int preferredPeriod() {
        return preferredPeriod;
    }

    /** 偏好周期 = 堆叠数，clamp [0, 64]（堆 1 叠 = 宽带，见 §3.4 因子二） */
    public void setPreferredPeriodFromStack(int stackCount) {
        this.preferredPeriod = Math.max(0, Math.min(64, stackCount));
    }
}
