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
 * 涂蜡雕文铜块 —— 输入/输出方向配置（§3.4 感应拓扑）。
 *
 * <p>雕文 = 1 线圈 × 1 向：只读取 {@code inputDir} 方向的边信号，
 * 只向 {@code outputDir} 方向传播相位事件（信号层同款 2 键 WASD 配置）。
 * 默认输入=↓ 输出=↑（下传上）。</p>
 */
public record LivingWaxedChiseledData(
    Pos2D inputDir,
    Pos2D outputDir
) implements TooltipProvider {

    public static final LivingWaxedChiseledData DEFAULT = new LivingWaxedChiseledData(Pos2D.DOWN, Pos2D.UP);

    public static final Codec<LivingWaxedChiseledData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Pos2D.CODEC.fieldOf("input_dir").forGetter(LivingWaxedChiseledData::inputDir),
            Pos2D.CODEC.fieldOf("output_dir").forGetter(LivingWaxedChiseledData::outputDir)
        ).apply(instance, LivingWaxedChiseledData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingWaxedChiseledData> STREAM_CODEC =
        StreamCodec.composite(
            Pos2D.STREAM_CODEC, LivingWaxedChiseledData::inputDir,
            Pos2D.STREAM_CODEC, LivingWaxedChiseledData::outputDir,
            LivingWaxedChiseledData::new
        );

    public LivingWaxedChiseledData withInputDir(Pos2D inputDir) {
        return new LivingWaxedChiseledData(inputDir, this.outputDir);
    }

    public LivingWaxedChiseledData withOutputDir(Pos2D outputDir) {
        return new LivingWaxedChiseledData(this.inputDir, outputDir);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder, TooltipFlag flag) {
    }
}