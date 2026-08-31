package com.qiqi.li.living.domain.power;

import java.util.HashMap;
import java.util.Map;

/**
 * 容器级红电数据 —— 与 {@code ContainerRedstoneData} 并列的电力层账本。
 *
 * <p>纯 Java 类，零 Minecraft 依赖（事件与数值全部由 glue 层喂入），
 * 可直接用 JUnit 驱动（见 {@code ContainerPowerDataTest}）。</p>
 *
 * <h3>记账模型（§3.6/§3.10 v17.5，事件驱动）</h3>
 * <ul>
 *   <li>跳变即能量事件：RE 累加为本 tick 发电量；tick 末由 glue 分配入铜灯
 *       （无铜灯则弃——发电必须被消费或储存）；</li>
 *   <li>EMA 平滑出「当前功率」读数（RE/t），对外经 K 换算为 FE；</li>
 *   <li>**无容器池**：铜灯是唯一储存，容器只是铜灯的架子（§3.6 v17.5）。</li>
 * </ul>
 */
public class ContainerPowerData {

    /** EMA 平滑系数（约 8 tick 记忆） */
    private static final double EMA_ALPHA = 0.125;

    private final Map<Integer, GeneratorState> generators = new HashMap<>();

    /** 本 tick 发电量（RE），由 glue 在 tick 末 drain 后分配入铜灯 */
    private long generatedReThisTick;

    private double emaPowerRe;
    private long tickCounter;
    private long lastTickTime = System.currentTimeMillis();

    public GeneratorState getOrCreateGenerator(int slot) {
        return generators.computeIfAbsent(slot, key -> new GeneratorState());
    }

    public GeneratorState getGenerator(int slot) {
        return generators.get(slot);
    }

    /** 当前内部 tick 计数（事件时间戳用） */
    public long currentTick() {
        return tickCounter;
    }

    /** 记录一次跳变产出的能量（RE）：累加为本 tick 发电量 */
    public void onEventEnergy(long re) {
        if (re <= 0) return;
        generatedReThisTick += re;
        lastTickTime = System.currentTimeMillis();
    }

    /**
     * 取走本 tick 累计发电量（RE），供 glue 按剩余容量比例分配入铜灯。
     */
    public long drainGeneratedRe() {
        long v = generatedReThisTick;
        generatedReThisTick = 0;
        return v;
    }

    /**
     * 每 tick 末尾调用（glue 在分配入灯之后）：EMA 更新 + tick 计数推进。
     *
     * @param generatedRe 本 tick 发电量（RE），即 {@link #drainGeneratedRe()} 的返回值
     */
    public void endTick(long generatedRe) {
        emaPowerRe += (generatedRe - emaPowerRe) * EMA_ALPHA;
        tickCounter++;
        lastTickTime = System.currentTimeMillis();
    }

    /** EMA 平滑后的功率读数（RE/t） */
    public double getEmaPowerRe() {
        return emaPowerRe;
    }

    /** EMA 功率换算为 FE/t（边界换算，K = 1/16） */
    public long getEmaPowerFe() {
        return Math.round(emaPowerRe * PowerMath.RE_TO_FE);
    }

    /** 供过期清理使用（镜像 {@code ContainerRedstoneData.getLastTickTime}） */
    public long getLastTickTime() {
        return lastTickTime;
    }
}