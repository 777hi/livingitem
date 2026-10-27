package com.qiqi.li.living.compat.sable;

/** Sable 飞艇传送的纯坐标计算。 */
final class SableTeleportMath {

    private SableTeleportMath() {
    }

    /**
     * 平移飞艇原点，使当前世界包围盒的底面落到目标 Y。
     *
     * <p>只做平移，不改变姿态：倾斜飞艇的世界 AABB 最低点同样适用。</p>
     */
    static double originYForBottom(double currentOriginY, double currentBottomY, double targetBottomY) {
        return currentOriginY + targetBottomY - currentBottomY;
    }
}
