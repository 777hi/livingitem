package com.qiqi.li.living.domain.farmland;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * 耕作知识表 —— 「什么算锄头」+「什么土能耕成什么」，活耕地获取路径的唯一真源。
 *
 * <p><b>锄头判定（2026-09-16 定稿：只认 ItemAbility）</b>：判定依据仅为
 * {@code ItemStack#canPerformAction(ItemAbilities.HOE_TILL)}。原版 {@code HoeItem}
 * 经 NeoForge patch 对 HOE_TILL 返回 true（{@code DEFAULT_HOE_ACTIONS}），
 * 故 6 种原版锄头无需枚举；重写了 {@code IItemExtension#canPerformAction} 的
 * 模组锄头（含多工具合一型）自动兼容。未声明该能力的自实现锄头<b>不</b>覆盖——
 * 这是刻意的取舍：不做 {@code instanceof HoeItem} / 标签 / 配置兜底，宁缺毋滥。</p>
 *
 * <p><b>可耕映射</b>对齐原版 {@code IBlockExtension#getToolModifiedState} 的
 * HOE_TILL 分支（neoforge-21.1.249 源码摘录）：
 * <pre>
 *   grass_block / dirt_path / dirt → farmland
 *   coarse_dirt / rooted_dirt      → dirt
 * </pre>
 * 原版的两处世界副作用——「目标上方必须是空气」与「缠根泥土掉落垂根」——在
 * 物品层 GUI 交互里没有世界上下文，一律不做。
 * podzol（灰化土）与 mycelium（菌丝）原版不可耕，不纳入。</p>
 */
public final class Tillables {

    /**
     * 可耕目标 → 产物。{@code coarse_dirt}/{@code rooted_dirt} 的产物是 {@code dirt}
     * 而非耕地——与原版一致，想要活耕地得再耕一跳。
     */
    private static final Map<Item, Item> TILLED_RESULTS = Map.of(
        Items.GRASS_BLOCK, Items.FARMLAND,
        Items.DIRT_PATH, Items.FARMLAND,
        Items.DIRT, Items.FARMLAND,
        Items.COARSE_DIRT, Items.DIRT,
        Items.ROOTED_DIRT, Items.DIRT
    );

    private Tillables() {}

    /**
     * 锄头判定：能否执行 HOE_TILL。原版锄头与声明了该能力的模组锄头均通过。
     */
    public static boolean isHoeLike(ItemStack stack) {
        return !stack.isEmpty() && stack.canPerformAction(ItemAbilities.HOE_TILL);
    }

    /**
     * 查表：目标物品耕后的产物物品，非可耕（含空栈）返回 {@code null}。
     */
    @Nullable
    public static Item tilledResultOf(ItemStack target) {
        return target.isEmpty() ? null : TILLED_RESULTS.get(target.getItem());
    }

    /**
     * 组合门槛 —— 客户端拦截（triggerFilter）与服务端校验共用同一份判定：
     * 光标是「活着的锄头」，目标是「活着的可耕物品」。
     *
     * <p>⚠️ 活物品自查不可省略：{@code InteractionRegistry.findInteraction} 的通配
     * 分支（triggerItem=null）不校验 trigger 的活物品标记，漏了这一步会让非活锄头
     * 也被拦截并吞掉原版的拿起/分堆操作。</p>
     */
    public static boolean canTillWith(ItemStack trigger, ItemStack target) {
        if (!LivingItemManager.isLivingItem(trigger)) return false;
        if (!LivingItemManager.isLivingItem(target)) return false;
        return isHoeLike(trigger) && tilledResultOf(target) != null;
    }

    /**
     * 可耕目标集合 —— 供注册处逐条注册交互规则（一个条目只能表达一个 targetItem）。
     */
    public static Set<Item> tillableTargets() {
        return TILLED_RESULTS.keySet();
    }
}
