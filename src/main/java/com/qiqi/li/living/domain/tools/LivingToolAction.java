package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

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
 * <p>⚠️ 现在<b>也被活武器复用</b>：{@code replayAttack} 用它记「上次攻击的 tick」算冷却
 * （见 {@code LivingToolReplay#replayAttack}）。TODO 蓄力型落地时应拆成独立组件。</p>
 *
 * @param tick   动作发生的世界轴 tick（客户端据此判断是否"刚发生"）
 * @param target 交互目标格子（客户端据此定位模型该瞬现到哪）；
 *               ⭐ <b>可为 {@code null}</b> —— 见 {@link #STREAM_CODEC} 的说明
 */
public record LivingToolAction(long tick, @Nullable BlockPos target) {

    /**
     * ⭐⭐ {@code target} <b>必须</b>按「可空」编码 —— 它有两种天然为 null 的场景：
     *
     * <ol>
     *   <li><b>右键空气 / 物品</b>（法杖施法走这条）：没有目标格子</li>
     *   <li><b>活武器攻击</b>：目标是<b>生物</b>，不是方块 ⇒ {@code replayAttack} 传 null</li>
     * </ol>
     *
     * <p>🔴 <b>事故（2026-09-24）</b>：曾用 {@code BlockPos.STREAM_CODEC} 直接编码。
     * 服务端发包时 {@code BlockPos.asLong()} 对 null 抛 NPE ⇒
     * {@code Failed to encode packet 'clientbound/minecraft:custom_payload'} ⇒
     * <b>玩家被踢出游戏</b>（表现为"存档崩了、游戏没崩"）。</p>
     *
     * <p>⇒ 凡是组件里带 {@code BlockPos} 且可能为 null，一律走 {@code optional}。</p>
     */
    // ⚠️ 手写而不是用 ByteBufCodecs.optional(BlockPos.STREAM_CODEC)：
    //    BlockPos.STREAM_CODEC 的缓冲类型是 ByteBuf（不是 FriendlyByteBuf），
    //    optional() 会把泛型 B 固定成 ByteBuf ⇒ 与 STREAM_CODEC 要求的 FriendlyByteBuf 不兼容。
    private static final StreamCodec<FriendlyByteBuf, BlockPos> OPTIONAL_BLOCK_POS =
            new StreamCodec<FriendlyByteBuf, BlockPos>() {
                @Override
                @Nullable
                public BlockPos decode(FriendlyByteBuf buf) {
                    return buf.readBoolean() ? buf.readBlockPos() : null;
                }

                @Override
                public void encode(FriendlyByteBuf buf, BlockPos pos) {
                    if (pos == null) {
                        buf.writeBoolean(false);
                    } else {
                        buf.writeBoolean(true);
                        buf.writeBlockPos(pos);
                    }
                }
            };

    public static final StreamCodec<FriendlyByteBuf, LivingToolAction> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, LivingToolAction::tick,
                OPTIONAL_BLOCK_POS, LivingToolAction::target,
                LivingToolAction::new);
}
