package com.qiqi.li.living.domain.redstone.piston;

import java.util.Objects;

import com.qiqi.li.living.model.Pos2D;

/** 槽位机械运行态；不持久化到物品，数量变化不影响有效性。 */
public record PistonRuntime(boolean extended, Pos2D direction) {

    public PistonRuntime {
        Objects.requireNonNull(direction);
    }

    public static PistonRuntime retracted(Pos2D direction) {
        return new PistonRuntime(false, direction);
    }

    public PistonRuntime alignDirection(Pos2D configuredDirection) {
        return direction.equals(configuredDirection) ? this : retracted(configuredDirection);
    }

    public PistonRuntime withExtended(boolean value) {
        return extended == value ? this : new PistonRuntime(value, direction);
    }
}
