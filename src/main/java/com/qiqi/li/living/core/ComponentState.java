package com.qiqi.li.living.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public class ComponentState {

    private final CompoundTag data;

    public ComponentState() {
        this.data = new CompoundTag();
    }

    public ComponentState(CompoundTag data) {
        this.data = data != null ? data : new CompoundTag();
    }

    public int getInt(String key, int defaultValue) {
        return data.contains(key) ? data.getInt(key) : defaultValue;
    }

    public void setInt(String key, int value) {
        data.putInt(key, value);
    }

    public float getFloat(String key, float defaultValue) {
        return data.contains(key) ? data.getFloat(key) : defaultValue;
    }

    public void setFloat(String key, float value) {
        data.putFloat(key, value);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return data.contains(key) ? data.getBoolean(key) : defaultValue;
    }

    public void setBoolean(String key, boolean value) {
        data.putBoolean(key, value);
    }

    public String getString(String key, String defaultValue) {
        return data.contains(key) ? data.getString(key) : defaultValue;
    }

    public void setString(String key, String value) {
        data.putString(key, value);
    }

    public ListTag getList(String key, int type) {
        return data.getList(key, type);
    }

    public void putList(String key, ListTag list) {
        data.put(key, list);
    }

    public boolean hasKey(String key) {
        return data.contains(key);
    }

    public CompoundTag toNBT() {
        return data.copy();
    }

    public static ComponentState fromNBT(CompoundTag tag) {
        return new ComponentState(tag);
    }
}