package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

/**
 * 客户端的「辅助挖掘进行中」状态（{@code A3} 模型的挖掘环用）。
 *
 * <p>由 {@link LivingToolAssist#onBreakSpeed} 在<b>客户端</b>侧写入 —— 原版破坏速度每 tick 算一次，
 * 所以"最近有没有收到事件"本身就能表达"玩家现在是不是在挖、挖的是哪一格"，
 * <b>不需要任何网络同步</b>（客户端本来就在跑同一套事件）。</p>
 *
 * <p>⚠️ 只放<b>两端都安全</b>的类型（{@code BlockPos} / 基本类型）——
 * 若引用任何客户端类，服务端加载本类时会崩。</p>
 */
public final class LivingToolAssistState {

    /**
     * 事件静默多久算「停止挖掘」（tick）。
     *
     * <p>挖掘进行中每 tick 都会触发一次，故取 3 已足够容错，又不会在松手后拖太久。</p>
     */
    private static final long IDLE_TICKS = 3L;

    private static BlockPos target;
    private static long lastNotedTick;
    private static float digSpeed;

    private LivingToolAssistState() {
    }

    /** 记录一次「正在挖」。{@code speed} 为含辅助贡献的最终破坏速度（供转速换算）。 */
    public static void note(BlockPos pos, long tick, float speed) {
        target = pos.immutable();
        lastNotedTick = tick;
        digSpeed = speed;
    }

    /**
     * 当前正在挖的方块。
     *
     * @param now 世界轴 tick
     * @return 目标方块；{@code null} = 当前没在挖
     */
    @Nullable
    public static BlockPos target(long now) {
        return target != null && now - lastNotedTick <= IDLE_TICKS ? target : null;
    }

    /** 含辅助贡献的破坏速度（用于转速）；没在挖时返回 0。 */
    public static float digSpeed() {
        return digSpeed;
    }

    /** 清空（客户端退出存档）。 */
    public static void clear() {
        target = null;
        lastNotedTick = 0L;
        digSpeed = 0.0F;
    }
}
