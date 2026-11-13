package com.qiqi.li.client.render.tlm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.util.Mth;

/** 只测我方展示角度；不创建第三方女仆，不代替实机渲染验收。 */
class SoulSlabPreviewFacingTest {
    private static void settle(SoulSlabPreviewFacing facing) {
        for (int i = 0; i < 20; i++) facing.tick();
    }

    @Test void initialBodyFacesPlayer() {
        var facing = new SoulSlabPreviewFacing();
        assertEquals(180, facing.body(0));
        assertEquals(180, facing.body(1));
        assertEquals(0, facing.headYaw(1));
        assertEquals(0, facing.headPitch(1));
    }

    @ParameterizedTest
    @CsvSource({"0,-18,0", "0,18,180", "-18,0,270", "18,0,90"})
    void fourScreenDirectionsDetermineBody(int dx, int dy, float expected) {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 18 + dx, 18 + dy);
        settle(facing);
        assertEquals(0, Mth.wrapDegrees(facing.body(1) - expected), 0.001);
    }

    @Test void stoppingKeepsLastBodyDirection() {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 0, 18);
        settle(facing);
        facing.look(20, 20, 500, -500, 1);
        settle(facing);
        assertEquals(270, facing.body(1));
    }

    @Test void crossingAngleBoundaryUsesShortestPath() {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 0, 18);
        settle(facing);
        facing.move(0, 18, 0, 0);
        facing.tick();
        assertEquals(270, facing.body(0));
        assertEquals(300, facing.body(1));
        assertEquals(285, facing.body(0.5f));
        settle(facing);
        assertEquals(0, Mth.wrapDegrees(facing.body(1)));
    }

    @Test void cursorYawIsLimitedRelativeToBody() {
        var facing = new SoulSlabPreviewFacing();
        facing.look(20, 20, -10000, 20, 1);
        settle(facing);
        assertEquals(45, facing.headYaw(1), 0.03);
        facing.look(20, 20, 10000, 20, 1);
        settle(facing);
        assertEquals(-45, facing.headYaw(1), 0.08);
    }

    @Test void backFacingReversesProjectionWithoutTurningHeadAround() {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 18, 0);
        settle(facing);
        facing.look(20, 20, 10000, 20, 1);
        settle(facing);
        assertEquals(0, Mth.wrapDegrees(facing.body(1)));
        assertTrue(facing.headYaw(1) > 0);
        assertTrue(Math.abs(facing.headYaw(1)) <= 45);
    }

    @Test void sideFacingAllowsSmallLookTowardViewer() {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 0, 18);
        settle(facing);
        facing.look(20, 20, 20, 20, 1);
        settle(facing);
        assertEquals(-30, facing.headYaw(1), 0.03);
        assertEquals(270, facing.body(1));
    }

    @Test void pitchIsLimitedAndSmooth() {
        var facing = new SoulSlabPreviewFacing();
        facing.look(20, 20, 20, -10000, 1);
        facing.tick();
        assertEquals(0, facing.headPitch(0));
        assertTrue(facing.headPitch(1) < 0 && facing.headPitch(1) > -20);
        settle(facing);
        assertEquals(-20, facing.headPitch(1), 0.03);
    }

    @Test void lookUsesCurrentDisplayedHeadPosition() {
        var facing = new SoulSlabPreviewFacing();
        facing.look(20, 20, 20, 20, 1);
        settle(facing);
        assertEquals(0, facing.headYaw(1));
        facing.look(38, 20, 20, 20, 1);
        settle(facing);
        assertTrue(facing.headYaw(1) > 0);
    }

    @Test void separatePreviewsKeepIndependentDirections() {
        var left = new SoulSlabPreviewFacing();
        var right = new SoulSlabPreviewFacing();
        left.move(18, 18, 0, 18);
        right.move(18, 18, 36, 18);
        settle(left);
        settle(right);
        assertEquals(270, left.body(1));
        assertEquals(90, right.body(1));
    }

    @Test void cursorModeReturnsBodyAndHeadTowardCenter() {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 0, 18);
        facing.look(20, 20, 500, -500, 1);
        settle(facing);
        facing.stopMoving();
        facing.centerLook();
        settle(facing);
        assertEquals(180, facing.body(1), 0.03);
        assertEquals(0, facing.headYaw(1), 0.03);
        assertEquals(0, facing.headPitch(1), 0.03);
    }

    @Test void nonMovementDoesNotResetBody() {
        var facing = new SoulSlabPreviewFacing();
        facing.move(18, 18, 0, 18);
        settle(facing);
        facing.move(0, 18, 0, 18);
        settle(facing);
        assertEquals(270, facing.body(1));
    }
}
