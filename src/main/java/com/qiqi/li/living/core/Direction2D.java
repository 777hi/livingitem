package com.qiqi.li.living.core;

public enum Direction2D {
    LEFT(-1, 0),
    RIGHT(1, 0),
    UP(0, -1),
    DOWN(0, 1),
    NONE(0, 0);

    public final int dx, dy;

    Direction2D(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }
}