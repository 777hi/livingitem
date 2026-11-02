package com.qiqi.li.living.container;

/**
 * 一步整栈移动的结果 —— <b>四态显式区分</b>，不用 boolean。
 *
 * <p>为什么要分四态而不是成功/失败两态（见 `buffer/move-api-minimal.md` §1）：</p>
 * <ul>
 *   <li><b>Rejected</b> = 正常拒绝，容器<b>没有违约</b>，重试可能成功（如目标被占）；</li>
 *   <li><b>Unsupported</b> = 后端根本不支持精确操作（如 handler 无 {@code setStackInSlot}），
 *       重试永远不会成功；</li>
 *   <li><b>BackendViolation</b> = 后端<b>撒谎</b>（模拟说能写、实际不符）—— 这是 bug 或兼容事故，
 *       必须留痕（{@code I-C6}），不能与「正常拒绝」混为一谈。</li>
 * </ul>
 *
 * <p>⚠️ <b>不设「部分成功」态</b>：活塞的语义是整栈移动，要么全成要么不动（`I-C13` 原地整槽分支）。
 * 部分交付属于漏斗压送的「移动交付量」（`I-C12`），那是另一个入口，不往本接口加参数。</p>
 */
public sealed interface MoveResult {

    /**
     * 成功。
     *
     * @param moved 实际移动数量 —— <b>恒等于操作开始时的源栈数量</b>
     * @param undo  本步的撤销凭据（只在本次 tick 内有效）
     */
    record Success(int moved, UndoToken undo) implements MoveResult {}

    /** 正常拒绝：状态未发生任何改变，重试可能成功。 */
    record Rejected(Reason reason) implements MoveResult {}

    /** 后端不支持精确操作：状态未改变，重试不会成功。 */
    record Unsupported(Reason reason) implements MoveResult {}

    /**
     * 后端违约：模拟与实际不符。实现已尽力回滚，调用方必须留痕。
     *
     * @param detail 具体不符点（写进日志用）
     */
    record BackendViolation(String detail) implements MoveResult {}

    /**
     * 拒绝原因 —— <b>少量稳定档位，不按模组细分</b>。
     *
     * <p>⚠️ 无法区分「准入拒绝」与「容量不足」时统一报 {@link #TARGET_INSUFFICIENT}，
     * <b>不要猜</b>：猜错会让排查走错方向，而这两者对玩家的处置是一样的（换个目标）。</p>
     */
    enum Reason {
        /** 源槽为空，没有可移动货物。 */
        SOURCE_EMPTY,
        /** 目标槽非空 —— 纯位移要求空槽（不合并）。 */
        TARGET_OCCUPIED,
        /** 容器拒绝完整取出（模拟抽取不足整栈）。 */
        SOURCE_NOT_EXTRACTABLE,
        /** 物品不允许进入目标槽（准入规则明确拒绝）。 */
        TARGET_ADMISSION_DENIED,
        /** 目标不能完整接收整栈；无法区分准入与容量时也报这档。 */
        TARGET_INSUFFICIENT,
        /** 越界 / 源目标相同 / 槽号非法。 */
        INVALID_ADDRESS
    }
}
