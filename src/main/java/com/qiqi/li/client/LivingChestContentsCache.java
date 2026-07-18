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
    private static int version = 0;

    public static List<ItemStack> get() {
        return Collections.unmodifiableList(contents);
    }

    public static void set(List<ItemStack> items) {
        contents.clear();
        for (ItemStack incoming : items) {
            if (incoming.isEmpty()) continue;
            boolean merged = false;
            for (ItemStack existing : contents) {
                if (ItemStack.isSameItemSameComponents(existing, incoming)) {
                    existing.grow(incoming.getCount());
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                contents.add(incoming.copy());
            }
        }
        version++;
    }

    public static int getVersion() {
        return version;
    }

    public static void adjustItem(ItemStack item, int delta) {
        for (int i = 0; i < contents.size(); i++) {
            ItemStack existing = contents.get(i);
            if (ItemStack.isSameItemSameComponents(existing, item)) {
                int newCount = existing.getCount() + delta;
                if (newCount <= 0) {
                    contents.remove(i);
                } else {
                    existing.setCount(newCount);
                }
                version++;
                return;
            }
        }
        if (delta > 0) {
            ItemStack copy = item.copy();
            copy.setCount(delta);
            contents.add(copy);
            version++;
        }
    }

    public static void clear() {
        contents.clear();
    }
}