package com.qiqi.li.client;

import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户端活箱子内容缓存。
 *
 * 用途：配方书标签页"活箱子"显示玩家背包中所有活箱子的合并内容。
 * 由 LivingChestContentsPacket 处理函数更新，由 RecipeBookComponentMixin 读取渲染。
 */
@OnlyIn(Dist.CLIENT)
public class LivingChestContentsCache {

    private static final List<ItemStack> contents = new ArrayList<>();
    private static boolean dirty = false;

    public static List<ItemStack> get() {
        return Collections.unmodifiableList(contents);
    }

    public static void set(List<ItemStack> items) {
        contents.clear();
        contents.addAll(items);
        dirty = false;
    }

    public static boolean isDirty() {
        return dirty;
    }

    public static void markDirty() {
        dirty = true;
    }

    public static void clear() {
        contents.clear();
        dirty = false;
    }
}