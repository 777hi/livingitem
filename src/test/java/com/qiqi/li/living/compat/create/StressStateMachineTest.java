package com.qiqi.li.living.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StressStateMachineTest {

    private static final float RPM = 8f;
    private static final float CAPACITY = 32f;

    @Test
    void firstActivationStoresStateBeforeCreatingNetworkAndPreservesOperationOrder() {
        StressStateMachine state = new StressStateMachine();
        FakeKineticOps ops = new FakeKineticOps();
        ops.onSetNetworkToSelf = () -> {
            assertEquals(RPM, state.getRPM(), "建网前必须已公开新转速");
            assertEquals(CAPACITY, state.getCapacity(), "建网会同步查询容量，必须先写状态");
        };

        state.applyStress(ops, RPM, CAPACITY);

        assertEquals(List.of(
            "speed:8.0", "detach", "network:self", "attach", "network:update:32.0", "changed", "send"
        ), ops.calls);
        assertTrue(state.isActive());
        assertEquals(RPM, state.getRPM());
        assertEquals(CAPACITY, state.getCapacity());
    }

    @Test
    void steadyRefreshOnlyUpdatesNetworkAccounting() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();

        state.applyStress(ops, RPM, 64f);

        assertEquals(List.of("network:update:64.0"), ops.calls);
        assertEquals(RPM, state.getRPM());
        assertEquals(64f, state.getCapacity());
    }

    @Test
    void epsilonSpeedChangeRemainsOnSteadyPath() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();

        state.applyStress(ops, 8.005f, 64f);

        assertEquals(List.of("network:update:64.0"), ops.calls);
        assertEquals(8.005f, state.getRPM());
    }

    @Test
    void nonzeroReversalReattachesWithoutReplacingNetworkIdentity() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();

        state.applyStress(ops, -RPM, 64f);

        assertEquals(List.of(
            "detach", "speed:-8.0", "attach", "network:update:64.0", "changed", "send"
        ), ops.calls);
        assertEquals(-RPM, state.getRPM());
        assertEquals(64f, state.getCapacity());
    }

    @Test
    void explicitClearRemovesBeforeDetachAndNeverReattaches() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();
        ops.onRemoveNetwork = () -> assertEquals(RPM, state.getRPM(), "移出网络前仍须保持源身份");

        state.applyStress(ops, 0, 0);

        assertEquals(List.of(
            "network:remove", "detach", "speed:0.0", "network:clear", "changed", "send"
        ), ops.calls);
        assertFalse(state.isActive());
        assertEquals(0, state.getCapacity());
    }

    @Test
    void diskRestoreIsSilentAndGrantsExactlyOneTickOfGrace() {
        StressStateMachine state = new StressStateMachine();
        FakeKineticOps ops = new FakeKineticOps();
        ops.network = true;

        state.restoreFromDisk(RPM, CAPACITY);
        state.tick(ops);

        assertTrue(ops.calls.isEmpty());
        assertTrue(state.isActive());

        state.tick(ops);

        assertEquals(List.of(
            "network:remove", "detach", "speed:0.0", "network:clear", "changed", "send"
        ), ops.calls);
        assertFalse(state.isActive());
    }

    @Test
    void refreshDuringDiskGraceUsesNetworkOnlyAndPreventsExpiry() {
        StressStateMachine state = new StressStateMachine();
        FakeKineticOps ops = new FakeKineticOps();
        ops.network = true;
        state.restoreFromDisk(RPM, CAPACITY);
        state.tick(ops);

        state.applyStress(ops, RPM, 64f);
        state.tick(ops);

        assertEquals(List.of("network:update:64.0"), ops.calls);
        assertTrue(state.isActive());
        assertEquals(64f, state.getCapacity());
    }

    @Test
    void staleGeneratingTargetUsesNativeRefreshInsteadOfGenericCleanup() {
        StressStateMachine state = new StressStateMachine();
        FakeKineticOps ops = new FakeKineticOps();
        ops.generating = true;
        ops.network = true;
        state.restoreFromDisk(RPM, CAPACITY);
        state.tick(ops);

        state.tick(ops);

        assertEquals(List.of("generator:update"), ops.calls);
        assertFalse(state.isActive());
        assertEquals(0, state.getCapacity());
    }

    @Test
    void chunkUnloadOnlyRemovesAndDetachesBeforeClearingLocalState() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();
        ops.onRemoveNetwork = () -> assertTrue(state.isActive(), "卸载移网时仍须保持源身份");

        state.onChunkUnloaded(ops);

        assertEquals(List.of("network:remove", "detach"), ops.calls);
        assertFalse(state.isActive());
        assertEquals(0, state.getCapacity());
    }

    @Test
    void unsafeTargetClearsRememberedStateWithoutTouchingKinetics() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();
        ops.safe = false;

        state.applyStress(ops, RPM, CAPACITY);

        assertTrue(ops.calls.isEmpty());
        assertFalse(state.isActive());
        assertEquals(0, state.getCapacity());
    }

    @Test
    void offServerApplyOnlyUpdatesMemoryAndLifecycleHooksAreNoops() {
        StressStateMachine state = new StressStateMachine();
        FakeKineticOps ops = new FakeKineticOps();
        ops.serverSide = false;

        state.applyStress(ops, RPM, CAPACITY);
        state.tick(ops);
        state.onChunkUnloaded(ops);

        assertTrue(ops.calls.isEmpty());
        assertTrue(state.isActive());
        assertEquals(CAPACITY, state.getCapacity());
    }

    @Test
    void clearContinuesAfterNetworkRemovalFailure() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();
        ops.fail("network:remove");

        state.applyStress(ops, 0, 0);

        assertEquals(List.of(
            "network:remove", "detach", "speed:0.0", "network:clear", "changed", "send"
        ), ops.calls);
        assertFalse(state.isActive());
    }

    @Test
    void networkUpdateFailureDoesNotSkipDirtyMarkOrSync() {
        StressStateMachine state = new StressStateMachine();
        FakeKineticOps ops = new FakeKineticOps();
        ops.fail("network:update");

        state.applyStress(ops, RPM, CAPACITY);

        assertEquals(List.of(
            "speed:8.0", "detach", "network:self", "attach", "network:update:32.0", "changed", "send"
        ), ops.calls);
        assertTrue(state.isActive());
        assertEquals(CAPACITY, state.getCapacity());
    }

    @Test
    void unloadAttemptsDetachEvenWhenNetworkRemovalFails() {
        StressStateMachine state = activeState();
        FakeKineticOps ops = activeOps();
        ops.fail("network:remove");
        ops.fail("detach");

        state.onChunkUnloaded(ops);

        assertEquals(List.of("network:remove", "detach"), ops.calls);
        assertFalse(state.isActive());
        assertEquals(0, state.getCapacity());
    }

    private static StressStateMachine activeState() {
        StressStateMachine state = new StressStateMachine();
        state.restoreFromDisk(RPM, CAPACITY);
        return state;
    }

    private static FakeKineticOps activeOps() {
        FakeKineticOps ops = new FakeKineticOps();
        ops.network = true;
        return ops;
    }

    private static final class FakeKineticOps implements KineticOps {
        final List<String> calls = new ArrayList<>();
        final Set<String> failures = new HashSet<>();
        boolean serverSide = true;
        boolean safe = true;
        boolean generating;
        boolean network;
        Runnable onSetNetworkToSelf = () -> {};
        Runnable onRemoveNetwork = () -> {};

        void fail(String operation) {
            failures.add(operation);
        }

        private void record(String call, String operation) {
            calls.add(call);
            if (failures.contains(operation)) {
                throw new IllegalStateException(operation);
            }
        }

        @Override
        public boolean livingItem$isServerSide() {
            return serverSide;
        }

        @Override
        public boolean livingItem$isSafeForStressInjection() {
            return safe;
        }

        @Override
        public boolean livingItem$tryUpdateGeneratedRotation() {
            if (!generating) return false;
            record("generator:update", "generator:update");
            return true;
        }

        @Override
        public boolean livingItem$hasNetwork() {
            return network;
        }

        @Override
        public void livingItem$removeFromNetwork() {
            record("network:remove", "network:remove");
            onRemoveNetwork.run();
        }

        @Override
        public void livingItem$detachKinetics() {
            record("detach", "detach");
        }

        @Override
        public void livingItem$attachKinetics() {
            record("attach", "attach");
        }

        @Override
        public void livingItem$setSpeed(float rpm) {
            record("speed:" + rpm, "speed");
        }

        @Override
        public void livingItem$setNetworkToSelf() {
            record("network:self", "network:self");
            onSetNetworkToSelf.run();
            network = true;
        }

        @Override
        public void livingItem$clearNetwork() {
            record("network:clear", "network:clear");
            network = false;
        }

        @Override
        public void livingItem$updateNetwork(float capacity) {
            record("network:update:" + capacity, "network:update");
        }

        @Override
        public void livingItem$setChanged() {
            record("changed", "changed");
        }

        @Override
        public void livingItem$sendData() {
            record("send", "send");
        }

        @Override
        public String livingItem$getDebugType() {
            return "FakeKinetic";
        }

        @Override
        public String livingItem$getDebugPosition() {
            return "test";
        }
    }
}
