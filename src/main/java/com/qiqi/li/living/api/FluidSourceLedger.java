package com.qiqi.li.living.api;

import javax.annotation.Nullable;

import net.neoforged.neoforge.fluids.FluidType;

/**
 * 派生源「账本」读写视图 —— 供<b>跨领域</b>消费者（活海绵吸收）增删派生源，而不必 import 水领域。
 *
 * <p>{@link FluidPresence} 的姊妹契约：存在性只读视图之外，吸收还需要
 * 「源是什么流体」（单一流体规则判定）与「删源」（账本即时，与汲走同层）。
 * 实现者：{@code ContainerFluidData}（水领域）；{@code TickContext} 只认本契约。</p>
 *
 * <p>⚠️ 账本即时性：{@link #removeSource} 立即从账本移除（同拍后续消费者不再看见），
 * 世界侧（CA / 渲染）按引擎既有层级生效——与汲走同层，不是「下一拍才删」。</p>
 */
public interface FluidSourceLedger {

    /** 指定槽位的派生源流体；非派生源返回 {@code null}（不区分干槽 / 流动格 / 异种原因）。 */
    @Nullable
    FluidType sourceAt(int slot);

    /** 删除指定槽位的派生源（汲走同款「整源消耗」口径）；非派生源槽位无效果。 */
    void removeSource(int slot);
}
