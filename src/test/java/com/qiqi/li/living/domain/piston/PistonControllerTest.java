package com.qiqi.li.living.domain.piston;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.model.Pos2D;

class PistonControllerTest {

    @Test
    void poweredRetractedPistonRequestsPush() {
        var transition = PistonController.next(PistonRuntime.retracted(Pos2D.RIGHT), Pos2D.RIGHT, 15, false);
        assertEquals(PistonController.Action.PUSH, transition.action());
        assertFalse(transition.state().extended());
    }

    @Test
    void successfulPushHoldsWhileSignalRemains() {
        var transition = PistonController.next(PistonRuntime.retracted(Pos2D.RIGHT), Pos2D.RIGHT, 1, false);
        var state = PistonController.complete(transition, true);
        assertTrue(state.extended());
        assertEquals(PistonController.Action.NONE, PistonController.next(state, Pos2D.RIGHT, 15, false).action());
    }

    @Test
    void failedPushCanRetryUnderContinuousPower() {
        var transition = PistonController.next(PistonRuntime.retracted(Pos2D.RIGHT), Pos2D.RIGHT, 1, false);
        var state = PistonController.complete(transition, false);
        assertFalse(state.extended());
        assertEquals(PistonController.Action.PUSH, PistonController.next(state, Pos2D.RIGHT, 1, false).action());
    }

    @Test
    void ordinaryPistonRetractsWithoutPull() {
        var transition = PistonController.next(new PistonRuntime(true, Pos2D.RIGHT), Pos2D.RIGHT, 0, false);
        assertEquals(PistonController.Action.NONE, transition.action());
        assertFalse(transition.state().extended());
    }

    @Test
    void stickyPistonRequestsPullAndRetractsEvenWhenPullFails() {
        var transition = PistonController.next(new PistonRuntime(true, Pos2D.RIGHT), Pos2D.RIGHT, 0, true);
        assertEquals(PistonController.Action.PULL, transition.action());
        assertFalse(PistonController.complete(transition, false).extended());
        assertFalse(PistonController.complete(transition, true).extended());
    }

    @Test
    void directionChangeWhilePoweredStartsNewCycleWithoutOldPull() {
        var transition = PistonController.next(new PistonRuntime(true, Pos2D.RIGHT), Pos2D.UP, 15, true);
        assertEquals(PistonController.Action.PUSH, transition.action());
        assertEquals(Pos2D.UP, transition.state().direction());
        assertFalse(transition.state().extended());
    }

    @Test
    void directionChangeWhileUnpoweredResetsWithoutPull() {
        var transition = PistonController.next(new PistonRuntime(true, Pos2D.RIGHT), Pos2D.UP, 0, true);
        assertEquals(PistonController.Action.NONE, transition.action());
        assertFalse(transition.state().extended());
    }

    @Test
    void equivalentDecodedDirectionDoesNotResetExtendedState() {
        var state = new PistonRuntime(true, Pos2D.RIGHT);
        var transition = PistonController.next(state, new Pos2D(1, 0), 15, true);
        assertSame(state, transition.state());
        assertEquals(PistonController.Action.NONE, transition.action());
    }

    @Test
    void forceChangesDoNotParticipateInRuntimeValidity() {
        var state = new PistonRuntime(true, Pos2D.RIGHT);
        assertSame(state, state.alignDirection(Pos2D.RIGHT));
        assertEquals(PistonController.Action.NONE, PistonController.next(state, Pos2D.RIGHT, 1, false).action());
    }

    @Test
    void discardedRuntimeMayPushAgainUnderExistingSignal() {
        var fresh = PistonRuntime.retracted(Pos2D.RIGHT);
        assertEquals(PistonController.Action.PUSH, PistonController.next(fresh, Pos2D.RIGHT, 15, true).action());
    }

    @Test
    void retractedPistonWithoutPowerDoesNothing() {
        var state = PistonRuntime.retracted(Pos2D.RIGHT);
        assertSame(state, PistonController.next(state, Pos2D.RIGHT, 0, true).state());
        assertEquals(PistonController.Action.NONE, PistonController.next(state, Pos2D.RIGHT, 0, true).action());
    }

    @Test
    void invalidDirectionCannotRequestMechanicalAction() {
        var transition = PistonController.next(new PistonRuntime(true, Pos2D.RIGHT), Pos2D.UP_RIGHT, 15, true);
        assertEquals(PistonController.Action.NONE, transition.action());
        assertFalse(transition.state().extended());
    }

    @Test
    void runtimeRejectsMissingDirection() {
        assertThrows(NullPointerException.class, () -> new PistonRuntime(false, null));
    }
}
