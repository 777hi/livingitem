package com.qiqi.li.living.model;

/**
 * 已解析的槽位坐标载体。
 *
 * <p>封装熔炉式的 input / fuel / output 三槽，或漏斗式的 source / target 双槽，以及各自的方向偏移
 * （{@code Pos2D}），是槽位解析与跨容器传输之间的中间表示。</p>
 */
public record ResolvedSlots(
    int inputSlot,
    int fuelSlot,
    int outputSlot,
    int sourceSlot,
    int targetSlot,
    Pos2D sourceOffset,
    Pos2D targetOffset
) {
    public static ResolvedSlots empty() {
        return new ResolvedSlots(-1, -1, -1, -1, -1, Pos2D.NONE, Pos2D.NONE);
    }

    public static ResolvedSlots ofSlots(int inputSlot, int fuelSlot, int outputSlot) {
        return new ResolvedSlots(inputSlot, fuelSlot, outputSlot, -1, -1, Pos2D.NONE, Pos2D.NONE);
    }

    public static ResolvedSlots ofTransfer(int sourceSlot, int targetSlot, Pos2D sourceOffset, Pos2D targetOffset) {
        return new ResolvedSlots(-1, -1, -1, sourceSlot, targetSlot, sourceOffset, targetOffset);
    }
}