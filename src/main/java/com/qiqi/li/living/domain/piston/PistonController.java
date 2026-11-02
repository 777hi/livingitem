package com.qiqi.li.living.domain.piston;

import com.qiqi.li.living.model.Pos2D;

/** 机械决策与动作结果分离；拉取失败仍收回，推失败保留重试资格。 */
public final class PistonController {

    private PistonController() {}

    public enum Action {
        NONE,
        PUSH,
        PULL
    }

    public record Transition(PistonRuntime state, Action action) {}

    public static Transition next(PistonRuntime state, Pos2D direction, int signal, boolean sticky) {
        PistonRuntime current = state.alignDirection(direction);
        if (PistonChainResolver.edgeDirection(direction) < 0) {
            return new Transition(PistonRuntime.retracted(direction), Action.NONE);
        }
        if (signal > 0 && !current.extended()) return new Transition(current, Action.PUSH);
        if (signal <= 0 && current.extended()) {
            return new Transition(current.withExtended(false), sticky ? Action.PULL : Action.NONE);
        }
        return new Transition(current, Action.NONE);
    }

    /** 推的成功包括实际交付量大于零；此处不负责接收或搬运货物。 */
    public static PistonRuntime complete(Transition transition, boolean success) {
        return switch (transition.action()) {
            case PUSH -> transition.state().withExtended(success);
            case PULL, NONE -> transition.state();
        };
    }
}
