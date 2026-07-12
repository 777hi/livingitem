package com.qiqi.li.living.core.model;

import net.minecraft.nbt.CompoundTag;

/**
 * 不可变二维坐标模型 —— 表示容器中的相对方向偏移。
 *
 * 使用 Java record 实现不可变性，确保线程安全和数据一致性。
 * 用于 DirectionModeComponent 的槽位方向配置和 SlotResolver 的槽位解析。
 *
 * 坐标系说明：
 *   - x 轴：水平方向，左为负（-1），右为正（+1）
 *   - y 轴：垂直方向，上为负（-1），下为正（+1）
 *   - 原点 (0,0)：活物品所在的槽位
 *
 * 预定义常量：
 *   - 四个基本方向：UP(0,-1), DOWN(0,1), LEFT(-1,0), RIGHT(1,0)
 *   - 四个对角方向：UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
 *   - 特殊值：NONE(0,0) 表示无偏移
 *
 * 使用场景：
 *   - SLOTS 模式：Pos2D 定义命名槽位相对于活物品的方向
 *     例如：input=LEFT 表示输入槽位在活熔炉左边
 *   - TRANSFER 模式：SlotMapping 包含两个 Pos2D（源和目标）
 *     例如：source=UP, target=DOWN 表示从上方取物品放到下方
 */
public record Pos2D(int x, int y) {

    /** 无偏移 —— 表示自身或无效方向 */
    public static final Pos2D NONE = new Pos2D(0, 0);

    /** 向上偏移（y-1）—— 活物品上方的槽位 */
    public static final Pos2D UP = new Pos2D(0, -1);

    /** 向下偏移（y+1）—— 活物品下方的槽位 */
    public static final Pos2D DOWN = new Pos2D(0, 1);

    /** 向左偏移（x-1）—— 活物品左边的槽位 */
    public static final Pos2D LEFT = new Pos2D(-1, 0);

    /** 向右偏移（x+1）—— 活物品右边的槽位 */
    public static final Pos2D RIGHT = new Pos2D(1, 0);

    /** 左上对角偏移（x-1, y-1） */
    public static final Pos2D UP_LEFT = new Pos2D(-1, -1);

    /** 右上对角偏移（x+1, y-1） */
    public static final Pos2D UP_RIGHT = new Pos2D(1, -1);

    /** 左下对角偏移（x-1, y+1） */
    public static final Pos2D DOWN_LEFT = new Pos2D(-1, 1);

    /** 右下对角偏移（x+1, y+1） */
    public static final Pos2D DOWN_RIGHT = new Pos2D(1, 1);

    /**
     * 获取方向的 Unicode 符号表示。
     *
     * 用于 Tooltip 显示，提供直观的方向指示：
     * ↑ ↓ ← → ↖ ↗ ↙ ↘ ·
     *
     * @return 方向符号字符串
     */
    public String getSymbol() {
        if (this.equals(UP)) return "↑";
        if (this.equals(DOWN)) return "↓";
        if (this.equals(LEFT)) return "←";
        if (this.equals(RIGHT)) return "→";
        if (this.equals(UP_LEFT)) return "↖";
        if (this.equals(UP_RIGHT)) return "↗";
        if (this.equals(DOWN_LEFT)) return "↙";
        if (this.equals(DOWN_RIGHT)) return "↘";
        return "·";
    }

    /**
     * 计算两个向量的和。
     *
     * @param other 要加上的向量
     * @return 新的向量（this + other）
     */
    public Pos2D add(Pos2D other) {
        return new Pos2D(this.x + other.x, this.y + other.y);
    }

    /**
     * 取反向量（180° 反向）。
     *
     * @return 取反后的新向量
     */
    public Pos2D negate() {
        return new Pos2D(-this.x, -this.y);
    }

    /**
     * 判断是否为基本方向（上下左右）。
     *
     * @return 如果是 UP/DOWN/LEFT/RIGHT 返回 true
     */
    public boolean isCardinal() {
        return this == UP || this == DOWN || this == LEFT || this == RIGHT;
    }

    /**
     * 判断是否为对角方向。
     *
     * @return 如果是四个对角方向之一返回 true
     */
    public boolean isDiagonal() {
        return this == UP_LEFT || this == UP_RIGHT || this == DOWN_LEFT || this == DOWN_RIGHT;
    }

    /**
     * 判断是否为零向量（无偏移）。
     *
     * @return 如果 x 和 y 都为 0 返回 true
     */
    public boolean isNone() {
        return this.x == 0 && this.y == 0;
    }

    /**
     * 从 NBT 标签中读取 Pos2D 数据。
     *
     * @param tag NBT 复合标签
     * @param prefix 键名前缀（如 "slot_input"）
     * @return 解析出的 Pos2D 对象
     */
    public static Pos2D fromNBT(CompoundTag tag, String prefix) {
        int x = tag.getInt(prefix + "_x");
        int y = tag.getInt(prefix + "_y");
        return new Pos2D(x, y);
    }

    /**
     * 将 Pos2D 数据写入 NBT 标签。
     *
     * 写入格式：{prefix}_x 和 {prefix}_y 两个整数键
     *
     * @param tag 目标 NBT 复合标签
     * @param prefix 键名前缀
     */
    public void toNBT(CompoundTag tag, String prefix) {
        tag.putInt(prefix + "_x", this.x);
        tag.putInt(prefix + "_y", this.y);
    }
}