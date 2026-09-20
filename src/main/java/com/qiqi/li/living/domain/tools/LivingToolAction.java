package com.qiqi.li.living.domain.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 活工具最近一次<b>瞬时动作</b>（{@code K} 组模型动画用，<b>仅网络同步、不落盘</b>）。
 *
 * <p>交互（右键：去皮 / 耕地 / 铺路）是<b>一瞬间</b>完成的 —— 不像挖掘有
 * {@code LIVING_TOOL_PROGRESS} 那种持续状态可查，客户端<b>无从得知"刚刚发生了交互"</b>，
 * 也不知道"交互发生在哪一格"（容器形态起点埋在方块里，客户端 {@code clip} 必然自命中）。</p>
 *
 * <p>故由服务端在 {@code useOn} 成功时写下这一条，客户端据此播一次
 * 「瞬现到交互位 → 缩放脉冲 → 飞回」。</p>
 *
 * @param tick   动作发生的世界轴 tick（客户端据此判断是否"刚发生"）
 * @param target 交互目标格子（客户端据此定位模型该瞬现到哪）
 */
public record LivingToolAction(long tick, BlockPos target) {

    public static final StreamCodec<FriendlyByteBuf, LivingToolAction> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, LivingToolAction::tick,
                BlockPos.STREAM_CODEC, LivingToolAction::target,
                LivingToolAction::new);
}
