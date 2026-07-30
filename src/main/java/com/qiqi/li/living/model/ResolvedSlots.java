package com.qiqi.li.living.model;

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