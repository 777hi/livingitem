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
    long emaPowerFe,         // 本机 EMA 功率（FE/t，取整）
    long levelEmaPowerFe,    // 本锈级 EMA 功率（FE/t，取整；v18 取代旧容器总功率）
    List<DomainSnapshot> domains,  // 全部域快照（F3+H 高级显示用）
    double resonanceGain,    // 网络级共振增益 R^2（容器级，见 §3.6.1）；1.0=无共振
    double resonanceBalance, // 平衡度 s ∈ [0,1]（各锈级基础出力接近程度）
    int activeLevels,        // 活跃锈级数 N（1~4；0=无发电）
    List<Long> levelPower    // 各锈蚟级基础出力 EMA（RE/t，长度=OXIDATION_LEVELS），诊断用
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
            Codec.LONG.fieldOf("ema_power_fe").forGetter(LivingWaxedGeneratorData::emaPowerFe),
            Codec.LONG.fieldOf("level_ema_power_fe").forGetter(LivingWaxedGeneratorData::levelEmaPowerFe),
            DomainSnapshot.CODEC.listOf().fieldOf("domains").forGetter(LivingWaxedGeneratorData::domains),
            Codec.DOUBLE.fieldOf("resonance_gain").forGetter(LivingWaxedGeneratorData::resonanceGain),
            Codec.DOUBLE.fieldOf("resonance_balance").forGetter(LivingWaxedGeneratorData::resonanceBalance),
            Codec.INT.fieldOf("active_levels").forGetter(LivingWaxedGeneratorData::activeLevels),
            Codec.LONG.listOf().fieldOf("level_power").forGetter(LivingWaxedGeneratorData::levelPower)
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
                ByteBufCodecs.VAR_LONG.encode(buf, v.emaPowerFe);
                ByteBufCodecs.VAR_LONG.encode(buf, v.levelEmaPowerFe);
                DomainSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, v.domains);
                ByteBufCodecs.DOUBLE.encode(buf, v.resonanceGain);
                ByteBufCodecs.DOUBLE.encode(buf, v.resonanceBalance);
                ByteBufCodecs.VAR_INT.encode(buf, v.activeLevels);
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).encode(buf, v.levelPower);
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
     * @param deltas       各偏移的 |Δ|，按偏移升序（偏移 0 的 |Δ| 在数组首）
     */
    public record DomainSnapshot(
        int period,
        int n,
        int maxDelta,
        double effDeltaSum,
        List<Integer> deltas
    ) {
        public static final Codec<DomainSnapshot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.INT.fieldOf("period").forGetter(DomainSnapshot::period),
                Codec.INT.fieldOf("n").forGetter(DomainSnapshot::n),
                Codec.INT.fieldOf("max_delta").forGetter(DomainSnapshot::maxDelta),
                Codec.DOUBLE.fieldOf("eff_delta_sum").forGetter(DomainSnapshot::effDeltaSum),
                Codec.INT.listOf().fieldOf("deltas").forGetter(DomainSnapshot::deltas)
            ).apply(instance, DomainSnapshot::new)
        );

        public static final StreamCodec<RegistryFriendlyByteBuf, DomainSnapshot> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DomainSnapshot::period,
                ByteBufCodecs.VAR_INT, DomainSnapshot::n,
                ByteBufCodecs.VAR_INT, DomainSnapshot::maxDelta,
                ByteBufCodecs.DOUBLE, DomainSnapshot::effDeltaSum,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DomainSnapshot::deltas,
                DomainSnapshot::new
            );
    }
}