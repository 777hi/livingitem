package com.qiqi.li.living.domain.power;

import java.util.HashMap;
import java.util.Map;

/**
 * 容器级红电数据 —— 与 {@code ContainerRedstoneData} 并列的电力层账本。
 *
 * <p>纯 Java 类，零 Minecraft 依赖（事件与数值全部由 glue 层喂入），
 * 可直接用 JUnit 驱动（见 {@code ContainerPowerDataTest}）。</p>
 *
 * <h3>记账模型（§3.10，事件驱动）</h3>
 * <ul>
 *   <li>跳变即能量事件：RE 直接累加，无功率流中间态；</li>
 *   <li>EMA 平滑出「当前功率」读数（RE/t），对外经 K 换算为 FE；</li>
 *   <li>tick 计数器自维护（{@link #endTick()} 每 tick 递增），事件时间戳即由此而来。</li>
 * </ul>
 */
public class ContainerPowerData {

    /** EMA 平滑系数（约 8 tick 记忆） */
    private static final double EMA_ALPHA = 0.125;

    private final Map<Integer, GeneratorState> generators = new HashMap<>();

    private long reThisTick;
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

    /** 记录一次跳变产出的能量（RE） */
    public void onEventEnergy(long re) {
        if (re <= 0) return;
        reThisTick += re;
        lastTickTime = System.currentTimeMillis();
    }

    /**
     * 每 tick 末尾调用（由 glue 的 {@code tickContainerData} 驱动）：
     * EMA 更新 + tick 计数推进。
     */
    public void endTick() {
        emaPowerRe += (reThisTick - emaPowerRe) * EMA_ALPHA;
        reThisTick = 0;
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
