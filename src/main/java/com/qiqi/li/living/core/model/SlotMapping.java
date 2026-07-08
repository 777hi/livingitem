package com.qiqi.li.living.core.model;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

public record SlotMapping(
    Pos2D sourceOffset,
    Pos2D targetOffset,
    String displaySymbol,
    String displayName
) {

    public static final SlotMapping UP_TO_DOWN = new SlotMapping(Pos2D.UP, Pos2D.DOWN, "↑→↓", "上传下");
    public static final SlotMapping DOWN_TO_UP = new SlotMapping(Pos2D.DOWN, Pos2D.UP, "↓→↑", "下传上");
    public static final SlotMapping LEFT_TO_RIGHT = new SlotMapping(Pos2D.LEFT, Pos2D.RIGHT, "←→→", "左传右");
    public static final SlotMapping RIGHT_TO_LEFT = new SlotMapping(Pos2D.RIGHT, Pos2D.LEFT, "→→←", "右传左");
    public static final SlotMapping UP_TO_LEFT = new SlotMapping(Pos2D.UP, Pos2D.LEFT, "↑→←", "上传左");
    public static final SlotMapping UP_TO_RIGHT = new SlotMapping(Pos2D.UP, Pos2D.RIGHT, "↑→→", "上传右");
    public static final SlotMapping DOWN_TO_LEFT = new SlotMapping(Pos2D.DOWN, Pos2D.LEFT, "↓→←", "下传左");
    public static final SlotMapping DOWN_TO_RIGHT = new SlotMapping(Pos2D.DOWN, Pos2D.RIGHT, "↓→→", "下传右");
    public static final SlotMapping LEFT_TO_UP = new SlotMapping(Pos2D.LEFT, Pos2D.UP, "←→↑", "左传上");
    public static final SlotMapping LEFT_TO_DOWN = new SlotMapping(Pos2D.LEFT, Pos2D.DOWN, "←→↓", "左传下");
    public static final SlotMapping RIGHT_TO_UP = new SlotMapping(Pos2D.RIGHT, Pos2D.UP, "→→↑", "右传上");
    public static final SlotMapping RIGHT_TO_DOWN = new SlotMapping(Pos2D.RIGHT, Pos2D.DOWN, "→→↓", "右传下");

    public static final List<SlotMapping> PRESETS = List.of(
        UP_TO_DOWN, DOWN_TO_UP, LEFT_TO_RIGHT, RIGHT_TO_LEFT,
        UP_TO_LEFT, UP_TO_RIGHT, DOWN_TO_LEFT, DOWN_TO_RIGHT,
        LEFT_TO_UP, LEFT_TO_DOWN, RIGHT_TO_UP, RIGHT_TO_DOWN
    );

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("src_x", sourceOffset.x());
        tag.putInt("src_y", sourceOffset.y());
        tag.putInt("tgt_x", targetOffset.x());
        tag.putInt("tgt_y", targetOffset.y());
        tag.putString("symbol", displaySymbol);
        tag.putString("name", displayName);
        return tag;
    }

    public static SlotMapping fromNBT(CompoundTag tag) {
        if (tag == null || !tag.contains("src_x")) return null;

        Pos2D source = new Pos2D(tag.getInt("src_x"), tag.getInt("src_y"));
        Pos2D target = new Pos2D(tag.getInt("tgt_x"), tag.getInt("tgt_y"));

        for (SlotMapping preset : PRESETS) {
            if (preset.sourceOffset().equals(source) && preset.targetOffset().equals(target)) {
                return preset;
            }
        }

        String symbol = tag.getString("symbol");
        String name = tag.getString("name");

        return new SlotMapping(source, target,
            symbol.isEmpty() ? source.getSymbol() + "→" + target.getSymbol() : symbol,
            name.isEmpty() ? "自定义" : name);
    }
}