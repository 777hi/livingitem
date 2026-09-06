package com.qiqi.li.living.domain.redstone;

/**
 * 红电感知端口 —— 电力层与跨层消费者（漏斗锁定 / TNT 点燃）读取信号层的**唯一**接口。
 *
 * <p><b>设计动机（v19.1 架构演进 ②）</b>：edgeGrid 的语义变更（如 v15「共享边 →
 * 每槽自有出边」）曾同时波及电力层采样、漏斗锁定、TNT 点燃三个消费者，且漏了两个
 * 靠游戏实测才发现。收口到一个端口后，边模型的后续重构只改端口实现。</p>
 *
 * <p><b>感知语义</b>：{@code sensedSignal(slot, dir)} = <b>dir 方向邻居朝本槽发出的出边</b>的值
 * ——即「本槽从 dir 方向收到的信号」。边界（邻居越界）返回 0：涂蜡元件不感应容器外
 * 信号（设计决策，见 tooltip-system.md / living-power-tech.md）。</p>
 *
 * <p><b>消费者</b>：电力层 BFS 采样（涂蜡发电机的振荡感知）、活漏斗锁定、活 TNT 点燃。
 * 信号层内部（红石传播、充能）不经过本端口——那是信号层自己的读写路径。</p>
 */
public interface RedstoneSensor {

    /** 边方向数（UP/DOWN/LEFT/RIGHT） */
    int DIRECTIONS = 4;

    /**
     * 当前 tick：slot 从 dir 方向感知到的信号值。
     *
     * @param slot 感知元件所在槽位
     * @param dir  方向（0=UP 1=DOWN 2=LEFT 3=RIGHT）
     * @return 信号值（0 = 无信号）
     */
    int sensedSignal(int slot, int dir);

    /**
     * 上一 tick 同方向的感知值（与 {@link #sensedSignal} 配合做跳变检测）。
     */
    int prevSensedSignal(int slot, int dir);

    /**
     * 四方向感知值的最大值（漏斗锁定 / TNT 点燃等「任意边有信号」语义）。
     */
    int maxSensedSignal(int slot);
}
