package com.qiqi.li.living.domain.redstone;

/**
 * 红石传播稳态值对象 —— 封装「跳过整段 calculate」所需的所有跨 tick 持久状态。
 *
 * <p>物品修订计数与外部输入签名都不变、且无在途倒计时定时器时，本 tick 传播结果
 * 与上一 tick 完全一致，可复用 edgeGrid 直接跳过整段 calculate，省去 BFS 等开销。
 * 跳过是安全的：物品变更会 bump 修订计数（rev 变），邻居/原版红石变化会改变外部输入
 * 签名；唯一不受这两者驱动的逐 tick 演化是中继器 delayTimer / 按钮 pulseTimer 倒计时，
 * 故用 hadActiveTimers 作保险——只要有倒计时在跑就强制重算，绝不冻结时序。</p>
 *
 * <p>不可变设计：每次 {@link #withSkip} 或 {@link #withRecord} 产生新实例，
 * 消除字段遗漏更新风险。</p>
 */
record SteadyState(
    long revision,
    int externalSig,
    boolean hadActiveTimers,
    boolean everCalculated,
    int skipCount,
    long lastTickTime
) {
    /** 初始状态（未计算过，跳过计数为 0） */
    static SteadyState initial() {
        return new SteadyState(-1, 0, false, false, 0, System.currentTimeMillis());
    }

    /**
     * 本 tick 是否可以跳过整段 calculate。
     *
     * @param currentRev    当前容器修订计数
     * @param currentSig    当前外部输入签名
     * @param hasActiveTimers 当前是否有在途倒计时
     */
    boolean canSkip(long currentRev, int currentSig, boolean hasActiveTimers) {
        return everCalculated
            && currentRev == revision
            && currentSig == externalSig
            && !hasActiveTimers;
    }

    /**
     * 记录一次跳过：跳过计数 +1，时间戳刷新，其余不变。
     */
    SteadyState withSkip() {
        return new SteadyState(
            revision, externalSig, hadActiveTimers, everCalculated,
            skipCount + 1, System.currentTimeMillis()
        );
    }

    /**
     * 记录一次实算后的新稳态。
     *
     * <p>注意：revision 必须取<b>本次实算结束后</b>的修订计数，而非计算开始前的采样值——
     * 传播过程中经 syncSlotToClients 会 bump 修订计数，若记开始前的值会导致 lastRev 落后一拍，
     * 下一拍开头采到的 rev 永远与之不等，稳态跳过几乎无法触发。</p>
     *
     * @param newRev       本次实算后容器修订计数
     * @param newSig       本次实算外部输入签名
     * @param activeTimers 本次实算是否有在途倒计时
     */
    SteadyState withRecord(long newRev, int newSig, boolean activeTimers) {
        return new SteadyState(
            newRev, newSig, activeTimers, true,
            skipCount, System.currentTimeMillis()
        );
    }
}