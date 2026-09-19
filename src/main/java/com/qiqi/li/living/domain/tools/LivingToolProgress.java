package com.qiqi.li.living.domain.tools;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活工具当前正在挖的目标与起始时刻（{@code L21}）。
 *
 * <p>组件缺失 = 当前没在挖。存在即表示"正在挖 {@code target}，从 {@code startTick} 开始"。</p>
 *
 * <p><b>为什么只存这两个字段</b>：原版用的是<b>重算式</b>而非累加式
 * （{@code ServerPlayerGameMode#incrementDestroyProgress}）：
 * <pre>
 *     progress = 单 tick 进度 × (已过 tick 数 + 1)
 * </pre>
 * 所以不需要存浮点进度，只要记住"从哪一刻开始挖"，每 tick 重算即可 —— 写入量极小
 * （只在开始挖时写一次）。</p>
 *
 * <p>⚠️ {@code startTick} 用<b>世界轴</b> {@code level.getGameTime()}，
 * 不是 {@code ServerPlayerGameMode#gameTicks} —— 后者依赖 {@code gameMode.tick()}，
 * 而 FakePlayer 的 tick 是空实现（{@code L27}）。</p>
 */
public record LivingToolProgress(BlockPos target, long startTick) {

    public static final Codec<LivingToolProgress> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BlockPos.CODEC.fieldOf("target").forGetter(LivingToolProgress::target),
            Codec.LONG.fieldOf("start_tick").forGetter(LivingToolProgress::startTick)
        ).apply(instance, LivingToolProgress::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LivingToolProgress> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, LivingToolProgress::target,
            ByteBufCodecs.VAR_LONG, LivingToolProgress::startTick,
            LivingToolProgress::new
        );

    /** 目标变了 → 进度必须重置（{@code L23}）。 */
    public boolean isFor(BlockPos pos) {
        return target.equals(pos);
    }
}
