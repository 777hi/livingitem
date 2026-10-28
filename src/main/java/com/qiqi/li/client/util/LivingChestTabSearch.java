package com.qiqi.li.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.world.item.ItemStack;

/**
 * 活箱子标签页的搜索过滤（纯函数，无实例状态，配合 {@link PinyinHelper}）。
 *
 * <p>匹配范围：物品显示名（含自定义名，支持中文 / 拼音全拼 / 首字母缩写 / 部分拼音）、
 * 物品注册 ID。大小写不敏感。搜索词须先归一化（trim + 小写）再传入。</p>
 */
public final class LivingChestTabSearch {

    private LivingChestTabSearch() {}

    /**
     * 过滤物品列表（不修改传入列表）。
     *
     * @param contents 原始物品列表
     * @param searchText 归一化后的搜索词；空词 ⇒ 原列表原样返回
     */
    public static List<ItemStack> filter(List<ItemStack> contents, String searchText) {
        if (searchText.isEmpty()) {
            return contents;
        }

        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack.isEmpty()) continue;

            if (matches(stack, searchText)) {
                result.add(stack);
            }
        }
        return result;
    }

    /**
     * 单个物品是否匹配搜索词。
     *
     * <p>边界语义（沿用原 {@code matchesSearchText}）：空搜索词恒不匹配；
     * 空物品 + 非空搜索词返回 true —— 实际过滤路径由 {@link #filter} 先剔除空栈，
     * 该分支不可达。</p>
     */
    public static boolean matches(ItemStack stack, String searchText) {
        if (stack.isEmpty() || searchText.isEmpty()) {
            return !searchText.isEmpty();
        }

        String name = stack.getHoverName().getString();
        if (name.toLowerCase(Locale.ROOT).contains(searchText)) {
            return true;
        }
        if (PinyinHelper.isPinyinMatch(name, searchText)) {
            return true;
        }

        String itemId = stack.getItem().toString().toLowerCase(Locale.ROOT);
        return itemId.contains(searchText);
    }
}
