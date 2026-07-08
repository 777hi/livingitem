package com.qiqi.li.living.core.model;

import net.minecraft.nbt.CompoundTag;

public record Pos2D(int x, int y) {

    public static final Pos2D NONE = new Pos2D(0, 0);
    public static final Pos2D UP = new Pos2D(0, -1);
    public static final Pos2D DOWN = new Pos2D(0, 1);
    public static final Pos2D LEFT = new Pos2D(-1, 0);
    public static final Pos2D RIGHT = new Pos2D(1, 0);
    public static final Pos2D UP_LEFT = new Pos2D(-1, -1);
    public static final Pos2D UP_RIGHT = new Pos2D(1, -1);
    public static final Pos2D DOWN_LEFT = new Pos2D(-1, 1);
    public static final Pos2D DOWN_RIGHT = new Pos2D(1, 1);

    public String getSymbol() {
        if (this == UP) return "↑";
        if (this == DOWN) return "↓";
        if (this == LEFT) return "←";
        if (this == RIGHT) return "→";
        if (this == UP_LEFT) return "↖";
        if (this == UP_RIGHT) return "↗";
        if (this == DOWN_LEFT) return "↙";
        if (this == DOWN_RIGHT) return "↘";
        return "·";
    }

    public Pos2D add(Pos2D other) {
        return new Pos2D(this.x + other.x, this.y + other.y);
    }

    public Pos2D negate() {
        return new Pos2D(-this.x, -this.y);
    }

    public boolean isCardinal() {
        return this == UP || this == DOWN || this == LEFT || this == RIGHT;
    }

    public boolean isDiagonal() {
        return this == UP_LEFT || this == UP_RIGHT || this == DOWN_LEFT || this == DOWN_RIGHT;
    }

    public boolean isNone() {
        return this.x == 0 && this.y == 0;
    }

    public static Pos2D fromNBT(CompoundTag tag, String prefix) {
        int x = tag.getInt(prefix + "_x");
        int y = tag.getInt(prefix + "_y");
        return new Pos2D(x, y);
    }

    public void toNBT(CompoundTag tag, String prefix) {
        tag.putInt(prefix + "_x", this.x);
        tag.putInt(prefix + "_y", this.y);
    }
}