package com.qiqi.li.client.render.tlm;

import com.mojang.blaze3d.vertex.PoseStack;

/** 复现原容器槽物品在模型变换前的GUI矩阵。 */
final class SoulSlabGuiPose {
    private static final float SLOT_Z = 100;
    private static final float ITEM_Z = 150;

    private SoulSlabGuiPose() {}

    static PoseStack slotOrigin(PoseStack background, int guiLeft, int guiTop) {
        PoseStack result = copy(background);
        result.translate(guiLeft, guiTop, SLOT_Z);
        return result;
    }

    static PoseStack itemModelPose(PoseStack slotOrigin, int x, int y) {
        PoseStack result = copy(slotOrigin);
        result.translate(x + 8, y + 8, ITEM_Z);
        result.scale(16, -16, 16);
        return result;
    }

    static PoseStack copy(PoseStack source) {
        PoseStack result = new PoseStack();
        result.last().pose().set(source.last().pose());
        result.last().normal().set(source.last().normal());
        return result;
    }
}
