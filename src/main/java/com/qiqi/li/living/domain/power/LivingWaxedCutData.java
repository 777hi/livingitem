package com.qiqi.li.living.domain.power;

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
import com.qiqi.li.living.model.Pos2D;

/**
 * 涂蜡切制铜块 —— 单线圈感应方向（§3.4 感应拓扑）。
 *
 * <p>切制 = 1 线圈 × 1 向：只读取 {@code senseDir} 方向的边信号，
 * 其余方向的杂讯进不来。WASD 配置（HasDirection，1 键）。</p>
 */
public record LivingWaxedCutData(
    Pos2D senseDir
) implements TooltipProvider {

    public static final LivingWaxedCutData DEFAULT = new LivingWaxedCutData(Pos2D.UP);

    public static final Codec<LivingWaxedCutData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("sense_dir").forGetter(LivingWaxedCutData::senseDir)
        ).apply(instance, LivingWaxedCutData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaxedCutData> STREAM_CODEC =
        StreamCodec.composite(
            Pos2D.STREAM_CODEC, LivingWaxedCutData::senseDir,
            LivingWaxedCutData::new
        );

    public LivingWaxedCutData withSenseDir(Pos2D senseDir) {
        return new LivingWaxedCutData(senseDir);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}
