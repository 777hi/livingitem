package com.qiqi.li.living.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 组件运行时状态 —— 管理组件的持久化数据。
 *
 * 包装 CompoundTag 提供类型安全的读写接口，
 * 用于存储组件在 tick 过程中产生的运行时数据
 * （如进度值、燃烧时间、冷却时间、方向坐标等）。
 *
 * 生命周期：
 * 1. 组件通过 createDefaultState() 创建初始状态
 * 2. FunctionExecutor 在 tick 前从物品 NBT 加载状态
 * 3. 组件在 tick 中读写状态数据
 * 4. FunctionExecutor 在 tick 后将状态保存回物品 NBT
 *
 * 数据持久化：
 *   ComponentState 的底层数据（CompoundTag）会被写入物品 NBT 中，
 *   键名为组件 ID（如 "progress"、"fuel"、"direction_mode"）。
 *   这确保了活物品跨容器迁移时状态不丢失。
 */
public class ComponentState {

    /** 底层 NBT 数据 */
    private final CompoundTag data;

    /** 创建空状态 */
    public ComponentState() {
        this.data = new CompoundTag();
    }

    /** 从现有 CompoundTag 创建状态 */
    public ComponentState(CompoundTag data) {
        this.data = data != null ? data : new CompoundTag();
    }

    /** 读取整数，键不存在时返回默认值 */
    public int getInt(String key, int defaultValue) {
        return data.contains(key) ? data.getInt(key) : defaultValue;
    }

    /** 写入整数 */
    public void setInt(String key, int value) {
        data.putInt(key, value);
    }

    /** 读取浮点数，键不存在时返回默认值 */
    public float getFloat(String key, float defaultValue) {
        return data.contains(key) ? data.getFloat(key) : defaultValue;
    }

    /** 写入浮点数 */
    public void setFloat(String key, float value) {
        data.putFloat(key, value);
    }

    /** 读取布尔值，键不存在时返回默认值 */
    public boolean getBoolean(String key, boolean defaultValue) {
        return data.contains(key) ? data.getBoolean(key) : defaultValue;
    }

    /** 写入布尔值 */
    public void setBoolean(String key, boolean value) {
        data.putBoolean(key, value);
    }

    /** 读取字符串，键不存在时返回默认值 */
    public String getString(String key, String defaultValue) {
        return data.contains(key) ? data.getString(key) : defaultValue;
    }

    /** 写入字符串 */
    public void setString(String key, String value) {
        data.putString(key, value);
    }

    /** 读取列表 */
    public ListTag getList(String key, int type) {
        return data.getList(key, type);
    }

    /** 写入列表 */
    public void putList(String key, ListTag list) {
        data.put(key, list);
    }

    /** 读取子复合标签 */
    public CompoundTag getCompound(String key) {
        return data.getCompound(key);
    }

    /** 写入子复合标签 */
    public void putCompound(String key, CompoundTag tag) {
        data.put(key, tag);
    }

    /** 检查键是否存在 */
    public boolean contains(String key) {
        return data.contains(key);
    }

    /** 检查键是否存在（别名） */
    public boolean hasKey(String key) {
        return data.contains(key);
    }

    /**
     * 导出为 NBT 标签（深拷贝）。
     *
     * 返回数据的副本，修改返回值不会影响原始状态。
     *
     * @return 数据的深拷贝
     */
    public CompoundTag toNBT() {
        return data.copy();
    }

    /**
     * 从 NBT 标签创建状态。
     *
     * @param tag NBT 数据
     * @return 新的 ComponentState 实例
     */
    public static ComponentState fromNBT(CompoundTag tag) {
        return new ComponentState(tag);
    }
}