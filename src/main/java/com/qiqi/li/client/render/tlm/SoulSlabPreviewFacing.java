package com.qiqi.li.client.render.tlm;

import net.minecraft.util.Mth;

/** 预览头身角度；屏幕四向决定身体，光标只改变受限的相对头部视线。 */
final class SoulSlabPreviewFacing {
    static final float FRONT = 180;
    static final float HEAD_YAW_LIMIT = 45;
    static final float HEAD_PITCH_LIMIT = 20;
    private static final float BODY_STEP = 30;

    private float body = FRONT;
    private float oldBody = FRONT;
    private float targetBody = FRONT;
    private float headYaw;
    private float oldHeadYaw;
    private float headPitch;
    private float oldHeadPitch;
    private float targetHeadYaw;
    private float targetHeadPitch;

    void move(int fromX, int fromY, int toX, int toY) {
        if (fromY == toY && fromX != toX) targetBody = toX < fromX ? 270 : 90;
        else if (fromX == toX && fromY != toY) targetBody = toY < fromY ? 0 : FRONT;
    }

    void look(float headX, float headY, float mouseX, float mouseY, float partial) {
        float currentBody = body(partial);
        float relativeBody = (currentBody - FRONT) * Mth.DEG_TO_RAD;
        float cursorYaw = (float) Math.atan((headX - mouseX) / 40) * 40;
        // 侧向时略向屏幕外看；背向时左右投影反转，不把头扭到身体正后方。
        targetHeadYaw = Mth.clamp(cursorYaw * Mth.cos(relativeBody) - 30 * Mth.sin(relativeBody),
                -HEAD_YAW_LIMIT, HEAD_YAW_LIMIT);
        targetHeadPitch = Mth.clamp((float) Math.atan((mouseY - headY) / 40) * 20,
                -HEAD_PITCH_LIMIT, HEAD_PITCH_LIMIT);
    }

    void centerLook() {
        targetHeadYaw = 0;
        targetHeadPitch = 0;
    }

    void stopMoving() {
        targetBody = FRONT;
    }

    void tick() {
        oldBody = body;
        oldHeadYaw = headYaw;
        oldHeadPitch = headPitch;
        body += Mth.clamp(Mth.wrapDegrees(targetBody - body), -BODY_STEP, BODY_STEP);
        headYaw = Mth.lerp(0.35f, headYaw, targetHeadYaw);
        headPitch = Mth.lerp(0.35f, headPitch, targetHeadPitch);
    }

    float body(float partial) {
        return Mth.rotLerp(Mth.clamp(partial, 0, 1), oldBody, body);
    }

    float headYaw(float partial) {
        return Mth.lerp(Mth.clamp(partial, 0, 1), oldHeadYaw, headYaw);
    }

    float headPitch(float partial) {
        return Mth.lerp(Mth.clamp(partial, 0, 1), oldHeadPitch, headPitch);
    }
}
