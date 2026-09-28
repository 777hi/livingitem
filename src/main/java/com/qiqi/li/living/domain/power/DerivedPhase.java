package com.qiqi.li.living.domain.power;

/**
 * 派生相位 —— 相位元件解读出的「驻波」条目（v19 相位解读三元件）。
 *
 * <p>驻波语义：条目描述一个稳定的周期波形（周期 / 偏移 / 幅度），注册在元件槽位上。
 * 电力层 BFS 访问到该槽位时，若 {@code now ≡ offset (mod period)} 则视为该驻波的
 * 上升沿，向通道注入一次 {@link PhaseEvent}——与真实振荡器的边采样同权入账，
 * 走同一套跳变门控与 offset 去重。</p>
 *
 * <p>三形态的解读规则（见 living-power-tech.md「相位解读元件」）：</p>
 * <ul>
 *   <li>{@link #KIND_SHIFT} 雕文移相器：读信号的「位置」——派生 (P, φ+1 mod P, δ)，可链式叠加；</li>
 *   <li>{@link #KIND_SPLIT} 切制裂相器：读信号的「另一半」——下降沿波形直接登记；</li>
 *   <li>{@link #KIND_ADD} 格栅相位加法器：读信号间的「关系」——同周期多路 Σφᵢ mod P 求和。</li>
 * </ul>
 *
 * @param period      驻波周期（tick）
 * @param offset      驻波偏移（0 ~ period-1，上升沿落在 now ≡ offset 的 tick）
 * @param delta       驻波幅度 |Δ|（派生侧取保守值：移相沿用源幅度、裂相取下降沿幅度、加法取各路最小）
 * @param kind        解读形态（KIND_*）
 * @param updatedTick 最近一次派生 tick（活性判定：停更超过存活窗口即熄灭修剪）
 */
public record DerivedPhase(int period, int offset, int delta, int kind, long updatedTick) {

    public static final int KIND_SHIFT = 0;
    public static final int KIND_SPLIT = 1;
    public static final int KIND_ADD = 2;
}
