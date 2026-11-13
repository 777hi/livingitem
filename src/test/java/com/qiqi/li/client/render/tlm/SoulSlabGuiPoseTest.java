package com.qiqi.li.client.render.tlm;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabGuiPoseTest {
    @Test
    void slotOriginReproducesContainerSlotDepth() {
        PoseStack background = new PoseStack();
        background.translate(3, 4, 5);

        PoseStack origin = SoulSlabGuiPose.slotOrigin(background, 20, 30);
        Vector3f point = origin.last().pose().transformPosition(new Vector3f());
        assertEquals(23, point.x(), 0.0001f);
        assertEquals(34, point.y(), 0.0001f);
        assertEquals(105, point.z(), 0.0001f);
    }

    @Test
    void itemModelPoseMatchesGuiGraphicsBeforeApplyTransform() {
        PoseStack origin = SoulSlabGuiPose.slotOrigin(new PoseStack(), 20, 30);
        PoseStack item = SoulSlabGuiPose.itemModelPose(origin, 7, 11);
        Vector3f zero = item.last().pose().transformPosition(new Vector3f());
        Vector3f one = item.last().pose().transformPosition(new Vector3f(1, 1, 1));

        assertEquals(35, zero.x(), 0.0001f);
        assertEquals(49, zero.y(), 0.0001f);
        assertEquals(250, zero.z(), 0.0001f);
        assertEquals(51, one.x(), 0.0001f);
        assertEquals(33, one.y(), 0.0001f);
        assertEquals(266, one.z(), 0.0001f);
    }

    @Test
    void floatingOriginUsesCurrentRenderFloatingItemPose() {
        PoseStack current = new PoseStack();
        current.translate(20, 30, 232);
        PoseStack origin = SoulSlabGuiPose.floatingOrigin(current);
        PoseStack item = SoulSlabGuiPose.itemModelPose(origin, 7, 11);
        Vector3f zero = item.last().pose().transformPosition(new Vector3f());

        assertEquals(35, zero.x(), 0.0001f);
        assertEquals(49, zero.y(), 0.0001f);
        assertEquals(382, zero.z(), 0.0001f);
        assertEquals(current.last().pose(), origin.last().pose());
    }

    @Test
    void helpersDoNotMutateBackgroundPose() {
        PoseStack background = new PoseStack();
        SoulSlabGuiPose.itemModelPose(SoulSlabGuiPose.slotOrigin(background, 20, 30), 7, 11);
        SoulSlabGuiPose.floatingOrigin(background);
        assertEquals(new PoseStack().last().pose(), background.last().pose());
    }
}
