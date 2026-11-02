package com.qiqi.li.living.domain.piston;

import java.util.List;

/** 活塞的只读动作计划；接收候选不代表入口或出口已经允许交付。 */
public sealed interface PistonPlan {

    record EmptyPush() implements PistonPlan {}

    record Push(List<Integer> cargoSlots, int destinationSlot) implements PistonPlan {
        public Push {
            cargoSlots = List.copyOf(cargoSlots);
        }
    }

    record Receiver(List<Integer> cargoSlots, int receiverSlot) implements PistonPlan {
        public Receiver {
            cargoSlots = List.copyOf(cargoSlots);
        }
    }

    record Blocked(Reason reason) implements PistonPlan {}

    enum Reason {
        INVALID_ADDRESS,
        INVALID_DIRECTION,
        INVALID_FORCE,
        BOUNDARY,
        CHAIN_TOO_LONG
    }
}
