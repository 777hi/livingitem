package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.tnt.LivingTntFunction;
import com.qiqi.li.living.domain.tools.LivingToolFunction;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 掉落物形态的<b>准入判据</b>（2026-10-23 真机实录）。
 *
 * <p>现场：把点燃的活 TNT 丢在地上，它<b>不会爆炸</b> —— 因为 {@code LivingItem#processEntityContainers}
 * 的白名单写死成「活工具 / 活武器」，活 TNT 在 {@code instanceof} 处就被短路掉，压根没进 tick 管线。</p>
 *
 * <p>这不是第一次：2026-09-24 同样的形状漏过<b>活剑</b>（当时只写了 {@code isLivingTool}）。
 * ⇒ 判据必须是「功能自声明」({@link LivingItemFunction#supportsDroppedItem()})，
 * 而不是「物品白名单」—— 白名单每加一个支持的功能都要回来改，漏改的表现是
 * 「功能看起来在跑、其实永远不生效」。</p>
 */
class DroppedItemSupportTest {

    /**
     * ⚠️ {@code registerFunction} <b>不去重</b>（每 new 一个实例就多一条），
     * 所以统一在 {@code @BeforeAll} 注册一次，别放进 {@code @Test}。
     */
    @BeforeAll
    static void registerFunctions() {
        LivingItemManager.registerFunction(new LivingTntFunction());
        LivingItemManager.registerFunction(new LivingToolFunction());
        LivingItemManager.registerFunction(new LivingChestFunction());
    }

    private static ItemStack living(ItemStack stack) {
        ItemStack result = stack.copy();
        LivingItemManager.setLiving(result, true);
        return result;
    }

    @Test
    @DisplayName("活 TNT ⇒ 支持掉落物（真机「丢在地上点燃了也不炸」的回归）")
    void livingTnt_supportsDroppedItem() {
        assertTrue(LivingItemManager.supportsDroppedItem(living(new ItemStack(Items.TNT))),
            "活 TNT 的爆炸点走 ContainerAnchor.AtEntity ⇒ 掉落物形态必须被 tick，否则点燃了也不会炸");
    }

    @Test
    @DisplayName("活工具 ⇒ 支持掉落物（既有形态不能被回退）")
    void livingTool_supportsDroppedItem() {
        assertTrue(LivingItemManager.supportsDroppedItem(living(new ItemStack(Items.DIAMOND_PICKAXE))),
            "掉落物形态的活工具一直能挖矿（L14=d 射线起点），改成声明式后不能丢");
    }

    @Test
    @DisplayName("未声明的功能 ⇒ 不支持（别把其它活物品一起拉进掉落物 tick）")
    void undeclaredFunction_notSupported() {
        assertFalse(LivingItemManager.supportsDroppedItem(living(new ItemStack(Items.CHEST))),
            "活箱子没有声明支持掉落物形态 ⇒ 准入必须挡住；放进来只会拿到 getBlockPos()==null 空转");
    }

    @Test
    @DisplayName("非活物品 ⇒ 不支持（判据不是「有功能类」而是「活 + 有功能」）")
    void plainItem_notSupported() {
        assertFalse(LivingItemManager.supportsDroppedItem(new ItemStack(Items.TNT)));
    }
}
