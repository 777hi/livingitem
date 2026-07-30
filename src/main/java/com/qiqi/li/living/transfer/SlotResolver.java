package com.qiqi.li.living.transfer;

import com.qiqi.li.living.model.Pos2D;

/**
 * 槽位解析器 —— 将相对方向偏移转换为容器中的绝对槽位索引。
 *
 * 基于 Minecraft 容器的 9 列网格布局，将活物品的相对方向（Pos2D）
 * 转换为容器中的绝对槽位索引。
 *
 * 网格布局说明：
 *   Minecraft 的标准容器（箱子、背包等）都是 9 列布局：
 *   ┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
 *   │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │  ← 第 0 行
 *   ├───┼───┼───┼───┼───┼───┼───┼───┼───┤
 *   │ 9 │10 │11 │12 │13 │14 │15 │16 │17 │  ← 第 1 行
 *   ├───┼───┼───┼───┼───┼───┼───┼───┼───┤
 *   │18 │19 │20 │21 │22 │23 │24 │25 │26 │  ← 第 2 行
 *   └───┴───┴───┴───┴───┴───┴───┴───┴───┘
 *
 *   例如：活熔炉在槽位 13（第 1 行第 4 列）
 *   - LEFT(-1,0) → 槽位 12（左边）
 *   - DOWN(0,1)  → 槽位 22（下边）
 *   - RIGHT(1,0) → 槽位 14（右边）
 *
 * 边界检查：
 *   - 左边界：newCol < 0 → 返回 -1
 *   - 右边界：newCol >= 9 → 返回 -1
 *   - 上边界：newRow < 0 → 返回 -1
 *   - 下边界：result >= containerSize → 返回 -1
 *   - 无偏移：direction == NONE → 返回 -1
 *
 * 注意：5 格漏斗等非标准容器也使用此解析器，
 * 因为漏斗的 5 个槽位在内部也是按 9 列布局索引的。
 */
public final class SlotResolver {

    /** Minecraft 标准容器的列数 */
    public static final int DEFAULT_WIDTH = 9;

    private SlotResolver() {}

    /**
     * 解析相对方向为绝对槽位索引（无容器大小限制）。
     *
     * @param baseSlot 基准槽位索引（活物品所在位置）
     * @param direction 相对方向偏移
     * @return 目标槽位索引，越界返回 -1
     */
    public static int resolve(int baseSlot, Pos2D direction) {
        return resolve(baseSlot, direction, Integer.MAX_VALUE, DEFAULT_WIDTH);
    }

    /**
     * 解析相对方向为绝对槽位索引（带容器大小限制）。
     *
     * 计算公式：result = (baseSlot / 9 + direction.y) * 9 + (baseSlot % 9 + direction.x)
     *
     * @param baseSlot 基准槽位索引（活物品所在位置）
     * @param direction 相对方向偏移（Pos2D）
     * @param containerSize 容器大小（用于下边界检查）
     * @return 目标槽位索引，越界返回 -1
     */
    public static int resolve(int baseSlot, Pos2D direction, int containerSize) {
        return resolve(baseSlot, direction, containerSize, DEFAULT_WIDTH);
    }

    /**
     * 解析相对方向为绝对槽位索引（带容器大小和列数限制）。
     *
     * 计算公式：result = (baseSlot / width + direction.y) * width + (baseSlot % width + direction.x)
     *
     * @param baseSlot 基准槽位索引（活物品所在位置）
     * @param direction 相对方向偏移（Pos2D）
     * @param containerSize 容器大小（用于下边界检查）
     * @param containerWidth 容器列数（GUI 宽度，标准容器为 9）
     * @return 目标槽位索引，越界返回 -1
     */
    public static int resolve(int baseSlot, Pos2D direction, int containerSize, int containerWidth) {
        if (direction == Pos2D.NONE) return -1;

        int row = baseSlot / containerWidth;
        int col = baseSlot % containerWidth;
        int newCol = col + direction.x();
        int newRow = row + direction.y();

        if (newCol < 0 || newCol >= containerWidth || newRow < 0) return -1;

        int result = newRow * containerWidth + newCol;

        if (result >= containerSize) return -1;

        return result;
    }

    /**
     * 批量解析多个方向为绝对槽位索引。
     *
     * @param baseSlot 基准槽位索引
     * @param directions 相对方向偏移数组
     * @return 槽位索引数组，越界的位置为 -1
     */
    public static int[] resolveAll(int baseSlot, Pos2D... directions) {
        return resolveAll(baseSlot, Integer.MAX_VALUE, DEFAULT_WIDTH, directions);
    }

    /**
     * 批量解析多个方向为绝对槽位索引（带容器大小限制）。
     *
     * @param baseSlot 基准槽位索引
     * @param containerSize 容器大小
     * @param directions 相对方向偏移数组
     * @return 槽位索引数组，越界的位置为 -1
     */
    public static int[] resolveAll(int baseSlot, int containerSize, Pos2D... directions) {
        return resolveAll(baseSlot, containerSize, DEFAULT_WIDTH, directions);
    }

    public static int[] resolveAll(int baseSlot, int containerSize, int containerWidth, Pos2D... directions) {
        int[] result = new int[directions.length];
        for (int i = 0; i < directions.length; i++) {
            result[i] = resolve(baseSlot, directions[i], containerSize, containerWidth);
        }
        return result;
    }
}