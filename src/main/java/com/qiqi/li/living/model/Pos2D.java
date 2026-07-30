package com.qiqi.li.living.model;

import java.util.LinkedHashMap;
import java.util.Map;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

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

    public static final Codec<Pos2D> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("x").forGetter(Pos2D::x),
            Codec.INT.fieldOf("y").forGetter(Pos2D::y)
        ).apply(instance, Pos2D::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, Pos2D> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.INT, Pos2D::x,
        net.minecraft.network.codec.ByteBufCodecs.INT, Pos2D::y,
        Pos2D::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, Map<String, Pos2D>> MAP_STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public Map<String, Pos2D> decode(RegistryFriendlyByteBuf buf) {
                int size = buf.readVarInt();
                Map<String, Pos2D> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = buf.readUtf();
                    map.put(key, Pos2D.STREAM_CODEC.decode(buf));
                }
                return map;
            }
            @Override
            public void encode(RegistryFriendlyByteBuf buf, Map<String, Pos2D> value) {
                buf.writeVarInt(value.size());
                for (var entry : value.entrySet()) {
                    buf.writeUtf(entry.getKey());
                    Pos2D.STREAM_CODEC.encode(buf, entry.getValue());
                }
            }
        };

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