package com.qiqi.li.living.domain.power;

import java.util.List;
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
 * 涂蜡发电机的检测仪表盘数据（v2）。
 *
 * <p>服务端事件驱动算出的检测值（周期/相数/解锁度）写回本组件，
 * 经槽位同步到客户端，tooltip 直接读取——**不在 NBT 语义之外、不查世界状态**。</p>
 *
 * <p>全部为展示快照，不影响任何结算（纯仪表，非账本）。</p>
 */
public record LivingWaxedGeneratorData(
    int detectedPeriod,      // 检测周期（tick），0 = 无信号/检测中
    int phaseCount,          // 相数 n（主输入路的周期域）
    int unlockPermille,      // 解锁度 u = 调谐效率 × (n/偏好周期)，× 1000（0..1000）
    int lastDelta,           // 主输入路上次跳变幅度（|Δ|，信号单位）
    List<Integer> waves,     // 4 条直连路 32-bit 滚动波形窗口（bit31 = 32t 前，bit0 = 最新）
    int coilForm,            // 线圈形态：0=铜块 1=雕文 2=切制 3=格栅
    long emaPowerFe,         // EMA 功率（FE/t，取整）
    List<PathSnapshot> paths // 全部路径快照（F3+H 高级显示用）
) implements TooltipProvider {

    /** 线圈形态常量 */
    public static final int FORM_BLOCK = 0;
    public static final int FORM_CHISELED = 1;
    public static final int FORM_CUT = 2;
    public static final int FORM_GRATE = 3;

    public static final LivingWaxedGeneratorData DEFAULT =
        new LivingWaxedGeneratorData(0, 0, 0, 0, List.of(), FORM_BLOCK, 0, List.of());

    public static final Codec<LivingWaxedGeneratorData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("detected_period").forGetter(LivingWaxedGeneratorData::detectedPeriod),
            Codec.INT.fieldOf("phase_count").forGetter(LivingWaxedGeneratorData::phaseCount),
            Codec.INT.fieldOf("unlock_permille").forGetter(LivingWaxedGeneratorData::unlockPermille),
            Codec.INT.fieldOf("last_delta").forGetter(LivingWaxedGeneratorData::lastDelta),
            Codec.INT.listOf().fieldOf("waves").forGetter(LivingWaxedGeneratorData::waves),
            Codec.INT.fieldOf("coil_form").forGetter(LivingWaxedGeneratorData::coilForm),
            Codec.LONG.fieldOf("ema_power_fe").forGetter(LivingWaxedGeneratorData::emaPowerFe),
            PathSnapshot.CODEC.listOf().fieldOf("paths").forGetter(LivingWaxedGeneratorData::paths)
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
                var w = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).decode(buf);
                int cf = ByteBufCodecs.VAR_INT.decode(buf);
                long epf = ByteBufCodecs.VAR_LONG.decode(buf);
                var ps = PathSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf);
                return new LivingWaxedGeneratorData(dp, pc, up, ld, w, cf, epf, ps);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, LivingWaxedGeneratorData v) {
                ByteBufCodecs.VAR_INT.encode(buf, v.detectedPeriod);
                ByteBufCodecs.VAR_INT.encode(buf, v.phaseCount);
                ByteBufCodecs.VAR_INT.encode(buf, v.unlockPermille);
                ByteBufCodecs.VAR_INT.encode(buf, v.lastDelta);
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).encode(buf, v.waves);
                ByteBufCodecs.VAR_INT.encode(buf, v.coilForm);
                ByteBufCodecs.VAR_LONG.encode(buf, v.emaPowerFe);
                PathSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, v.paths);
            }
        };

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }

    /**
     * 单条路径快照（F3+H 高级显示用）。
     *
     * @param channelIdx 所属通道索引
     * @param direction  方向（0=UP 1=DOWN 2=LEFT 3=RIGHT）
     * @param isDirect   直连路（true）或感应路（false）
     * @param waveBits   32-bit 滚动波形
     * @param lastDelta  最近跳变幅度
     * @param period     该路周期估计（tick）
     */
    public record PathSnapshot(
        int channelIdx,
        int direction,
        boolean isDirect,
        int waveBits,
        int lastDelta,
        int period
    ) {
        public static final Codec<PathSnapshot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.INT.fieldOf("ch").forGetter(PathSnapshot::channelIdx),
                Codec.INT.fieldOf("dir").forGetter(PathSnapshot::direction),
                Codec.BOOL.fieldOf("direct").forGetter(PathSnapshot::isDirect),
                Codec.INT.fieldOf("wave").forGetter(PathSnapshot::waveBits),
                Codec.INT.fieldOf("delta").forGetter(PathSnapshot::lastDelta),
                Codec.INT.fieldOf("period").forGetter(PathSnapshot::period)
            ).apply(instance, PathSnapshot::new)
        );

        public static final StreamCodec<RegistryFriendlyByteBuf, PathSnapshot> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PathSnapshot::channelIdx,
                ByteBufCodecs.VAR_INT, PathSnapshot::direction,
                ByteBufCodecs.BOOL, PathSnapshot::isDirect,
                ByteBufCodecs.VAR_INT, PathSnapshot::waveBits,
                ByteBufCodecs.VAR_INT, PathSnapshot::lastDelta,
                ByteBufCodecs.VAR_INT, PathSnapshot::period,
                PathSnapshot::new
            );
    }
}