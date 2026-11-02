package com.qiqi.li.living.domain.redstone.piston;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.MoveResult;
import com.qiqi.li.living.container.UndoToken;
import com.qiqi.li.living.model.Pos2D;

import org.slf4j.Logger;

/** 普通推拉的调用编排；底层移动与恢复只由容器框架实现。 */
public final class PistonActions {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PistonActions() {}

    public enum Status {
        SUCCESS,
        BLOCKED,
        RECEIVER_PENDING,
        REJECTED,
        UNSUPPORTED,
        BACKEND_VIOLATION,
        UNDO_FAILED
    }

    public record Result(Status status, MoveResult failure) {
        public boolean success() {
            return status == Status.SUCCESS;
        }
    }

    public static Result push(ContainerContext ctx, PistonPlan plan) {
        if (plan instanceof PistonPlan.EmptyPush) return new Result(Status.SUCCESS, null);
        if (plan instanceof PistonPlan.Receiver) return new Result(Status.RECEIVER_PENDING, null);
        if (!(plan instanceof PistonPlan.Push push) || push.cargoSlots().isEmpty()) {
            return new Result(Status.BLOCKED, null);
        }

        List<UndoToken> completed = new ArrayList<>();
        int target = push.destinationSlot();
        for (int i = push.cargoSlots().size() - 1; i >= 0; i--) {
            int source = push.cargoSlots().get(i);
            MoveResult move = ctx.moveStack(source, target);
            if (!(move instanceof MoveResult.Success success)) {
                Result failure = failure(move);
                // 逆序撤销全部已完成步骤 —— **不因某一格失败就停下**（2026-11-03 改）。
                // 依据：`ContainerContext#undo` 自带「状态不符即拒绝、不覆盖」的校验，
                // 所以继续调用只会多恢复、不会多破坏；停下则放弃本来能恢复的格，
                // 让链定格在「半推」状态（更难排查，也更难让玩家理解）。
                // ⚠️ 早期版本在此提前 return，那是 undo 还没有校验时的权宜之计。
                int undoFailures = 0;
                for (int j = completed.size() - 1; j >= 0; j--) {
                    UndoToken token = completed.get(j);
                    if (!ctx.undo(token)) {
                        undoFailures++;
                        // I-C6「丢必留痕」：数量与物品必须记，否则事后只能知道哪格没恢复
                        LOGGER.error("Living piston undo failed: container={}, {}x{} from={} to={}",
                            ctx.getContainerKey(), token.moved(),
                            token.sourceBefore().getHoverName().getString(), token.from(), token.to());
                    }
                }
                return undoFailures == 0 ? failure : new Result(Status.UNDO_FAILED, move);
            }
            completed.add(success.undo());
            target = source;
        }
        return new Result(Status.SUCCESS, null);
    }

    public static Result pull(ContainerContext ctx, int hostSlot, Pos2D direction) {
        int edge = PistonChainResolver.edgeDirection(direction);
        if (edge < 0 || !ctx.isValidSlot(hostSlot) || ctx.getWidth() < 1) {
            return new Result(Status.BLOCKED, null);
        }
        int target = ContainerContext.resolveNeighbor(hostSlot, edge, ctx.getSize(), ctx.getWidth());
        if (target < 0) return new Result(Status.BLOCKED, null);
        int source = ContainerContext.resolveNeighbor(target, edge, ctx.getSize(), ctx.getWidth());
        if (source < 0) return new Result(Status.BLOCKED, null);
        MoveResult move = ctx.moveStack(source, target);
        return move instanceof MoveResult.Success ? new Result(Status.SUCCESS, null) : failure(move);
    }

    private static Result failure(MoveResult move) {
        if (move instanceof MoveResult.Rejected) return new Result(Status.REJECTED, move);
        if (move instanceof MoveResult.Unsupported) return new Result(Status.UNSUPPORTED, move);
        MoveResult.BackendViolation violation = (MoveResult.BackendViolation) move;
        LOGGER.error("Living piston move backend violation: {}", violation.detail());
        return new Result(Status.BACKEND_VIOLATION, move);
    }
}
