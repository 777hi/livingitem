package com.qiqi.li.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活箱子标签页搜索过滤的纯函数单测（{@link LivingChestTabSearch}）。
 *
 * <p>钉四件可脱离 Minecraft 的事：<b>空搜索词原样返回</b>（不清空栈）、
 * <b>名称匹配</b>（中文 / 拼音全拼 / 首字母缩写 / 部分拼音，经 {@link PinyinHelper}）、
 * <b>注册 ID 匹配</b>、<b>空物品 / 空搜索词的边界判据</b>。
 * 名称匹配走 {@code CUSTOM_NAME} 字面量组件 —— 单测环境语言表未加载，
 * 原版物品名（translatable）不可依赖。</p>
 */
class LivingChestTabSearchTest {

    private static ItemStack named(String name, Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    // ============================================================
    // 空搜索词：原样返回
    // ============================================================

    @Test
    @DisplayName("空搜索词：原列表原样返回，空栈也不清理")
    void filter_emptySearch_returnsAsIs() {
        List<ItemStack> contents = List.of(
            named("钻石剑", Items.DIAMOND_SWORD),
            new ItemStack(Items.AIR)  // 空栈
        );

        List<ItemStack> result = LivingChestTabSearch.filter(contents, "");

        assertEquals(contents, result, "空搜索词 ⇒ 原列表原样返回（不过滤、不清空栈）");
    }

    @Test
    @DisplayName("非空搜索词：剔除空栈后再按关键词过滤")
    void filter_removesEmptyStacks() {
        List<ItemStack> contents = List.of(
            named("钻石剑", Items.DIAMOND_SWORD),
            new ItemStack(Items.AIR)  // 空栈
        );

        List<ItemStack> result = LivingChestTabSearch.filter(contents, "钻石");

        assertEquals(1, result.size(), "空栈被剔除");
        assertEquals("钻石剑", result.get(0).getHoverName().getString());
        assertEquals(2, contents.size(), "传入列表不被修改");
    }

    // ============================================================
    // 名称匹配（含拼音）
    // ============================================================

    @Test
    @DisplayName("名称匹配：中文关键词直接命中，不相关名称不命中")
    void matches_chineseName() {
        ItemStack sword = named("钻石剑", Items.DIAMOND_SWORD);
        ItemStack beef = named("熟牛肉", Items.COOKED_BEEF);

        assertTrue(LivingChestTabSearch.matches(sword, "钻石"), "「钻石」命中「钻石剑」");
        assertTrue(LivingChestTabSearch.matches(sword, "剑"), "「剑」命中「钻石剑」");
        assertFalse(LivingChestTabSearch.matches(beef, "钻石"), "「钻石」不命中「熟牛肉」");
    }

    @Test
    @DisplayName("拼音匹配：全拼 / 首字母缩写 / 部分拼音均命中")
    void matches_pinyin() {
        ItemStack sword = named("钻石剑", Items.DIAMOND_SWORD);

        assertTrue(LivingChestTabSearch.matches(sword, "zuanshi"), "全拼 zuanshi 命中钻石剑");
        assertTrue(LivingChestTabSearch.matches(sword, "zuan"), "部分拼音 zuan 命中钻石剑");
        assertTrue(LivingChestTabSearch.matches(sword, "zs"), "首字母缩写 zs 命中钻石剑");
    }

    @Test
    @DisplayName("英文名大小写不敏感（名侧小写化后包含）")
    void matches_englishNameCaseInsensitive() {
        ItemStack sword = named("Diamond Sword", Items.DIAMOND_SWORD);

        assertTrue(LivingChestTabSearch.matches(sword, "sword"), "小写搜索词命中含大写的英文名");
        assertFalse(LivingChestTabSearch.matches(sword, "apple"), "不相关英文词不命中");
    }

    // ============================================================
    // 注册 ID 匹配
    // ============================================================

    @Test
    @DisplayName("注册 ID 匹配：名称与拼音都不含关键词时，按物品 ID 兜底")
    void matches_registryId() {
        // 名称「红宝石」与它的拼音均不含 diamond ⇒ 只可能经 minecraft:diamond 命中
        ItemStack rubyDiamond = named("红宝石", Items.DIAMOND);
        ItemStack rubyBeef = named("红宝石", Items.COOKED_BEEF);

        assertTrue(LivingChestTabSearch.matches(rubyDiamond, "diamond"), "ID minecraft:diamond 兜底命中");
        assertFalse(LivingChestTabSearch.matches(rubyBeef, "diamond"), "ID 不含 diamond 则不命中");
    }

    // ============================================================
    // 边界判据
    // ============================================================

    @Test
    @DisplayName("空物品 + 非空搜索词 ⇒ 匹配（原守卫分支即如此；实际路径由 filter 先剔除空栈，不可达）")
    void matches_emptyStackMatchesQuirk() {
        assertTrue(LivingChestTabSearch.matches(ItemStack.EMPTY, "钻石"),
            "保持原 matchesSearchText 语义：守卫分支返回 !searchText.isEmpty()");
    }

    @Test
    @DisplayName("空搜索词恒不匹配（即便物品非空）—— 由调用方对空词早退兜底")
    void matches_emptySearchNeverMatches() {
        assertFalse(LivingChestTabSearch.matches(named("钻石剑", Items.DIAMOND_SWORD), ""));
    }
}
