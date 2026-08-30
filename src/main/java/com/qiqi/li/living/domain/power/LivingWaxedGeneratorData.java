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
 * 涂蜡发电机的检测仪表盘数据（§3.6 v17.5 阶段五）。
 *
 * <p>服务端事件驱动算出的检测值（周期/相数/规律度/解锁度）写回本组件，
 * 经槽位同步到客户端，tooltip 直接读取——**不在 NBT 语义之外、不查世界状态**
 * （机制见 living-item-infrastructure.md §11）。</p>
 *
 * <p>全部为展示快照，不影响任何结算（纯仪表，非账本）。</p>
 */
public record LivingWaxedGeneratorData(
    int detectedPeriod,      // 检测周期（tick），0 = 无信号/检测中
    int phaseCount,          // 相数 n（主输入路的周期域）
    int regularityPermille,  // 规律度 r × 1000（0..1000）
    int unlockPermille,      // 解锁度 = 调谐效率 × 规律度，× 1000（0..1000）
    int lastDelta,           // 主输入路上次跳变幅度（|Δ|，信号单位）
    List<Integer> waves      // 每条直连路的 16-bit 滚动波形窗口（bit15 = 16t 前，bit0 = 最新）
) implements TooltipProvider {

    public static final LivingWaxedGeneratorData DEFAULT =
        new LivingWaxedGeneratorData(0, 0, 0, 0, 0, List.of());

    public static final Codec<LivingWaxedGeneratorData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("detected_period").forGetter(LivingWaxedGeneratorData::detectedPeriod),
            Codec.INT.fieldOf("phase_count").forGetter(LivingWaxedGeneratorData::phaseCount),
            Codec.INT.fieldOf("regularity_permille").forGetter(LivingWaxedGeneratorData::regularityPermille),
            Codec.INT.fieldOf("unlock_permille").forGetter(LivingWaxedGeneratorData::unlockPermille),
            Codec.INT.fieldOf("last_delta").forGetter(LivingWaxedGeneratorData::lastDelta),
            Codec.INT.listOf().fieldOf("waves").forGetter(LivingWaxedGeneratorData::waves)
        ).apply(instance, LivingWaxedGeneratorData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaxedGeneratorData> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LivingWaxedGeneratorData::detectedPeriod,
            ByteBufCodecs.VAR_INT, LivingWaxedGeneratorData::phaseCount,
            ByteBufCodecs.VAR_INT, LivingWaxedGeneratorData::regularityPermille,
            ByteBufCodecs.VAR_INT, LivingWaxedGeneratorData::unlockPermille,
            ByteBufCodecs.VAR_INT, LivingWaxedGeneratorData::lastDelta,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), LivingWaxedGeneratorData::waves,
            LivingWaxedGeneratorData::new
        );

    public LivingWaxedGeneratorData withDetectedPeriod(int v) {
        return new LivingWaxedGeneratorData(v, phaseCount, regularityPermille, unlockPermille, lastDelta, waves);
    }

    public LivingWaxedGeneratorData withPhaseCount(int v) {
        return new LivingWaxedGeneratorData(detectedPeriod, v, regularityPermille, unlockPermille, lastDelta, waves);
    }

    public LivingWaxedGeneratorData withRegularityPermille(int v) {
        return new LivingWaxedGeneratorData(detectedPeriod, phaseCount, v, unlockPermille, lastDelta, waves);
    }

    public LivingWaxedGeneratorData withUnlockPermille(int v) {
        return new LivingWaxedGeneratorData(detectedPeriod, phaseCount, regularityPermille, v, lastDelta, waves);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}
