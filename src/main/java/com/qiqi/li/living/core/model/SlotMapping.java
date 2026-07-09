package com.qiqi.li.living.core.model;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * 不可变槽位映射模型 —— 定义活物品的传输方向。
 *
 * 使用 Java record 实现不可变性，包含源方向（sourceOffset）和目标方向（targetOffset），
 * 用于 DirectionModeComponent 的 TRANSFER 模式和 ItemTransferComponent 的传输逻辑。
 *
 * 数据结构：
 *   - sourceOffset：源槽位相对于活物品的偏移（从哪取物品）
 *   - targetOffset：目标槽位相对于活物品的偏移（往哪放物品）
 *   - displaySymbol：显示符号（如 "↑→↓"）
 *   - displayName：显示名称（如 "上传下"）
 *
 * 预设常量：
 *   12 种基本传输方向（4×3 排列，排除源=目标的情况），
 *   覆盖了所有上下左右之间的传输组合。
 *
 * NBT 序列化：
 *   使用纯整数坐标存储（src_x, src_y, tgt_x, tgt_y），
 *   避免字符串解析的歧义问题。
 *   fromNBT() 优先匹配预设常量，未匹配则创建自定义 SlotMapping。
 *
 * 使用示例：
 * <pre>
 * SlotMapping mapping = SlotMapping.UP_TO_DOWN;  // 上传下
 * CompoundTag tag = mapping.toNBT();              // 序列化
 * SlotMapping restored = SlotMapping.fromNBT(tag); // 反序列化
 * </pre>
 */
public record SlotMapping(
    Pos2D sourceOffset,
    Pos2D targetOffset,
    String displaySymbol,
    String displayName
) {

    /** 上传下：从上方取物品，放到下方 */
    public static final SlotMapping UP_TO_DOWN = new SlotMapping(Pos2D.UP, Pos2D.DOWN, "↑→↓", "上传下");

    /** 下传上：从下方取物品，放到上方 */
    public static final SlotMapping DOWN_TO_UP = new SlotMapping(Pos2D.DOWN, Pos2D.UP, "↓→↑", "下传上");

    /** 左传右：从左边取物品，放到右边 */
    public static final SlotMapping LEFT_TO_RIGHT = new SlotMapping(Pos2D.LEFT, Pos2D.RIGHT, "←→→", "左传右");

    /** 右传左：从右边取物品，放到左边 */
    public static final SlotMapping RIGHT_TO_LEFT = new SlotMapping(Pos2D.RIGHT, Pos2D.LEFT, "→→←", "右传左");

    /** 上传左：从上方取物品，放到左边 */
    public static final SlotMapping UP_TO_LEFT = new SlotMapping(Pos2D.UP, Pos2D.LEFT, "↑→←", "上传左");

    /** 上传右：从上方取物品，放到右边 */
    public static final SlotMapping UP_TO_RIGHT = new SlotMapping(Pos2D.UP, Pos2D.RIGHT, "↑→→", "上传右");

    /** 下传左：从下方取物品，放到左边 */
    public static final SlotMapping DOWN_TO_LEFT = new SlotMapping(Pos2D.DOWN, Pos2D.LEFT, "↓→←", "下传左");

    /** 下传右：从下方取物品，放到右边 */
    public static final SlotMapping DOWN_TO_RIGHT = new SlotMapping(Pos2D.DOWN, Pos2D.RIGHT, "↓→→", "下传右");

    /** 左传上：从左边取物品，放到上方 */
    public static final SlotMapping LEFT_TO_UP = new SlotMapping(Pos2D.LEFT, Pos2D.UP, "←→↑", "左传上");

    /** 左传下：从左边取物品，放到下方 */
    public static final SlotMapping LEFT_TO_DOWN = new SlotMapping(Pos2D.LEFT, Pos2D.DOWN, "←→↓", "左传下");

    /** 右传上：从右边取物品，放到上方 */
    public static final SlotMapping RIGHT_TO_UP = new SlotMapping(Pos2D.RIGHT, Pos2D.UP, "→→↑", "右传上");

    /** 右传下：从右边取物品，放到下方 */
    public static final SlotMapping RIGHT_TO_DOWN = new SlotMapping(Pos2D.RIGHT, Pos2D.DOWN, "→→↓", "右传下");

    /** 所有预设传输方向的列表 */
    public static final List<SlotMapping> PRESETS = List.of(
        UP_TO_DOWN, DOWN_TO_UP, LEFT_TO_RIGHT, RIGHT_TO_LEFT,
        UP_TO_LEFT, UP_TO_RIGHT, DOWN_TO_LEFT, DOWN_TO_RIGHT,
        LEFT_TO_UP, LEFT_TO_DOWN, RIGHT_TO_UP, RIGHT_TO_DOWN
    );

    /**
     * 将 SlotMapping 序列化为 NBT 标签。
     *
     * 写入格式：
     *   - src_x, src_y：源方向坐标
     *   - tgt_x, tgt_y：目标方向坐标
     *   - symbol：显示符号
     *   - name：显示名称
     *
     * @return 包含所有字段的 CompoundTag
     */
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

    /**
     * 从 NBT 标签反序列化 SlotMapping。
     *
     * 优先匹配预设常量（通过坐标比较），确保单例语义。
     * 如果不匹配任何预设，创建自定义 SlotMapping。
     *
     * @param tag NBT 复合标签
     * @return 解析出的 SlotMapping，如果标签无效返回 null
     */
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