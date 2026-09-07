package com.qiqi.li.living.domain.power;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * 涂蜡发电机的检测仪表盘数据（v3 —— 相位事件总线）。
 *
 * <p>服务端事件驱动算出的检测值（周期/相数/解锁度）写回本组件，
 * 经槽位同步到客户端，tooltip 直接读取——**不在 NBT 语义之外、不查世界状态**。</p>
 *
 * <p>全部为展示快照，不影响任何结算（纯仪表，非账本）。</p>
 */
public record LivingWaxedGeneratorData(
    int detectedPeriod,      // 检测周期（tick），0 = 无信号/检测中
    int phaseCount,          // 相数 n（最佳域）
    int unlockPermille,      // 解锁度 u = 调谐效率 × (n/偏好周期)，× 1000（0..1000）
    int lastDelta,           // 最佳域的最大 |Δ|（显示用）
    int effDeltaSumPermille, // Σ√|Δ_i| × 1000（定点显示，用于公式展示）
    int coilForm,            // 线圈形态：0=铜块 1=雕文(输入/输出定向WASD) 2=切制(H/V隔离) 3=格栅
    long emaPowerMilliFe,      // 本机显示均值功率（毫 FE 定点，v19.1；窗口均值无纹波）
    long levelEmaPowerMilliFe, // 本锈级显示均值功率（毫 FE 定点）
    List<DomainSnapshot> domains,  // 全部域快照（F3+H 高级显示用）
    double resonanceGain,    // 网络级共振增益 R^2（容器级，见 §3.6.1）；1.0=无共振
    double resonanceBalance, // 平衡度 s ∈ [0,1]（各锈级基础出力接近程度）
    int activeLevels,        // 活跃锈级数 N（1~4；0=无发电）
    List<Long> levelPowerMilliFe // 各锈蚟级显示均值功率（毫 FE 定点，长度=OXIDATION_LEVELS）
) implements TooltipProvider {

    /** 线圈形态常量 */
    public static final int FORM_BLOCK = 0;
    public static final int FORM_CHISELED = 1;
    public static final int FORM_CUT = 2;
    public static final int FORM_GRATE = 3;

    public static final LivingWaxedGeneratorData DEFAULT =
        new LivingWaxedGeneratorData(0, 0, 0, 0, 0, FORM_BLOCK, 0, 0, List.of(),
            1.0, 0.0, 0, List.of(0L, 0L, 0L, 0L));

    public static final Codec<LivingWaxedGeneratorData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("detected_period").forGetter(LivingWaxedGeneratorData::detectedPeriod),
            Codec.INT.fieldOf("phase_count").forGetter(LivingWaxedGeneratorData::phaseCount),
            Codec.INT.fieldOf("unlock_permille").forGetter(LivingWaxedGeneratorData::unlockPermille),
            Codec.INT.fieldOf("last_delta").forGetter(LivingWaxedGeneratorData::lastDelta),
            Codec.INT.fieldOf("eff_delta_sum_permille").forGetter(LivingWaxedGeneratorData::effDeltaSumPermille),
            Codec.INT.fieldOf("coil_form").forGetter(LivingWaxedGeneratorData::coilForm),
            Codec.LONG.fieldOf("ema_power_milli_fe").forGetter(LivingWaxedGeneratorData::emaPowerMilliFe),
            Codec.LONG.fieldOf("level_ema_power_milli_fe").forGetter(LivingWaxedGeneratorData::levelEmaPowerMilliFe),
            DomainSnapshot.CODEC.listOf().fieldOf("domains").forGetter(LivingWaxedGeneratorData::domains),
            Codec.DOUBLE.fieldOf("resonance_gain").forGetter(LivingWaxedGeneratorData::resonanceGain),
            Codec.DOUBLE.fieldOf("resonance_balance").forGetter(LivingWaxedGeneratorData::resonanceBalance),
            Codec.INT.fieldOf("active_levels").forGetter(LivingWaxedGeneratorData::activeLevels),
            Codec.LONG.listOf().fieldOf("level_power_milli_fe").forGetter(LivingWaxedGeneratorData::levelPowerMilliFe)
        ).apply(instance, LivingWaxedGeneratorData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaxedGeneratorData> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public LivingWaxedGeneratorData decode(RegistryFriendlyByteBuf buf) {
                int dp = ByteBufCodecs.VAR_INT.decode(buf);
                int pc = ByteBufCodecs.VAR_INT.decode(buf);
                int up = ByteBufCodecs.VAR_INT.decode(buf);
                int ld = ByteBufCodecs.VAR_INT.decode(buf);
                int es = ByteBufCodecs.VAR_INT.decode(buf);
                int cf = ByteBufCodecs.VAR_INT.decode(buf);
                long epf = ByteBufCodecs.VAR_LONG.decode(buf);
                long cepf = ByteBufCodecs.VAR_LONG.decode(buf);
                var ds = DomainSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf);
                double rg = ByteBufCodecs.DOUBLE.decode(buf);
                double rb = ByteBufCodecs.DOUBLE.decode(buf);
                int av = ByteBufCodecs.VAR_INT.decode(buf);
                var vp = ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).decode(buf);
                return new LivingWaxedGeneratorData(dp, pc, up, ld, es, cf, epf, cepf, ds, rg, rb, av, vp);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, LivingWaxedGeneratorData v) {
                ByteBufCodecs.VAR_INT.encode(buf, v.detectedPeriod);
                ByteBufCodecs.VAR_INT.encode(buf, v.phaseCount);
                ByteBufCodecs.VAR_INT.encode(buf, v.unlockPermille);
                ByteBufCodecs.VAR_INT.encode(buf, v.lastDelta);
                ByteBufCodecs.VAR_INT.encode(buf, v.effDeltaSumPermille);
                ByteBufCodecs.VAR_INT.encode(buf, v.coilForm);
                ByteBufCodecs.VAR_LONG.encode(buf, v.emaPowerMilliFe);
                ByteBufCodecs.VAR_LONG.encode(buf, v.levelEmaPowerMilliFe);
                DomainSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, v.domains);
                ByteBufCodecs.DOUBLE.encode(buf, v.resonanceGain);
                ByteBufCodecs.DOUBLE.encode(buf, v.resonanceBalance);
                ByteBufCodecs.VAR_INT.encode(buf, v.activeLevels);
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).encode(buf, v.levelPowerMilliFe);
            }
        };

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /**
     * 单域快照（F3+H 高级显示用）。
     *
     * @param period       域周期（tick）
     * @param n            相数（不同偏移数）
     * @param maxDelta     域内最大 |Δ|
     * @param effDeltaSum  Σ√|Δ_i|（实际值，非 permille）
     * @param offsets      各相位偏移 φᵢ，**按升序**；与 {@code deltas} 一一对应
     * @param deltas       各相位的 |Δᵢ|，与 {@code offsets} 同序（同一下标 = 同一路相位）
     */
    public record DomainSnapshot(
        int period,
        int n,
        int maxDelta,
        double effDeltaSum,
        List<Integer> offsets,
        List<Integer> deltas
    ) {
        /**
         * 环形最小相位间隔（tick）：相邻两路相位的最小间距，含跨周期回绕。
         *
         * <p>相位圆盘在 n 很大时辐条会挤在一起、读不出精确间距，这个数字是它的兜底读数。
         * 它同时也是「同相风险」的量化：值越小，说明有两路越接近合并成一路（n 会塌缩）。</p>
         *
         * @return 最小间隔（tick）；相位少于 2 路时返回 -1
         */
        public int minPhaseGap() {
            if (period <= 0 || offsets == null || offsets.size() < 2) return -1;
            int min = Integer.MAX_VALUE;
            for (int i = 1; i < offsets.size(); i++) {
                min = Math.min(min, offsets.get(i) - offsets.get(i - 1));
            }
            // 跨周期回绕：最后一路 → 下一周期的 0 点 → 第一路
            min = Math.min(min, offsets.get(0) + period - offsets.get(offsets.size() - 1));
            return min;
        }

        public static final Codec<DomainSnapshot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.INT.fieldOf("period").forGetter(DomainSnapshot::period),
                Codec.INT.fieldOf("n").forGetter(DomainSnapshot::n),
                Codec.INT.fieldOf("max_delta").forGetter(DomainSnapshot::maxDelta),
                Codec.DOUBLE.fieldOf("eff_delta_sum").forGetter(DomainSnapshot::effDeltaSum),
                // v19.2 起 offsets 是 DomainSnapshot 的必需字段：不做旧 NBT 兼容，
                // 缺字段直接暴露为格式错误，避免把缺失的真实相位数据静默伪装成无辐条。
                Codec.INT.listOf().fieldOf("offsets").forGetter(DomainSnapshot::offsets),
                Codec.INT.listOf().fieldOf("deltas").forGetter(DomainSnapshot::deltas)
            ).apply(instance, DomainSnapshot::new)
        );

        public static final StreamCodec<RegistryFriendlyByteBuf, DomainSnapshot> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DomainSnapshot::period,
                ByteBufCodecs.VAR_INT, DomainSnapshot::n,
                ByteBufCodecs.VAR_INT, DomainSnapshot::maxDelta,
                ByteBufCodecs.DOUBLE, DomainSnapshot::effDeltaSum,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DomainSnapshot::offsets,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DomainSnapshot::deltas,
                DomainSnapshot::new
            );
    }

    /**
     * 最佳域快照（本 record 顶层 {@code detectedPeriod} / {@code phaseCount} 的来源域）。
     *
     * <p>唯一性由构造保证：{@code domains} 来自 {@code ChannelState} 的
     * {@code period → PhaseDomain} 映射，period 在域集合内唯一，
     * 因此按 period 匹配有且仅有一个结果，不需要额外的「best」标记字段。</p>
     *
     * @return 最佳域；无信号（{@code detectedPeriod ≤ 0}）或无域时返回 {@code null}
     */
    public DomainSnapshot bestDomain() {
        if (detectedPeriod <= 0) return null;
        for (DomainSnapshot ds : domains) {
            if (ds.period() == detectedPeriod) return ds;
        }
        return null;
    }
}