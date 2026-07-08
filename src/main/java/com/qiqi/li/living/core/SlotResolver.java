package com.qiqi.li.living.core;

import com.qiqi.li.living.core.model.Pos2D;

public final class SlotResolver {

    private static final int CONTAINER_WIDTH = 9;

    private SlotResolver() {}

    public static int resolve(int baseSlot, Pos2D direction) {
        return resolve(baseSlot, direction, Integer.MAX_VALUE);
    }

    public static int resolve(int baseSlot, Pos2D direction, int containerSize) {
        if (direction == Pos2D.NONE) return -1;

        int row = baseSlot / CONTAINER_WIDTH;
        int col = baseSlot % CONTAINER_WIDTH;
        int newCol = col + direction.x();
        int newRow = row + direction.y();

        if (newCol < 0 || newCol >= CONTAINER_WIDTH || newRow < 0) return -1;

        int result = newRow * CONTAINER_WIDTH + newCol;

        if (result >= containerSize) return -1;

        return result;
    }

    public static int[] resolveAll(int baseSlot, Pos2D... directions) {
        return resolveAll(baseSlot, Integer.MAX_VALUE, directions);
    }

    public static int[] resolveAll(int baseSlot, int containerSize, Pos2D... directions) {
        int[] result = new int[directions.length];
        for (int i = 0; i < directions.length; i++) {
            result[i] = resolve(baseSlot, directions[i], containerSize);
        }
        return result;
    }
}