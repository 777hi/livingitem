package com.qiqi.li.testutil;

import org.mockito.Mockito;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * 模组锄头替身 —— 不继承 {@code HoeItem}，只声明 HOE_TILL 能力。
 *
 * <p>对应 NeoForge 对自定义工具的推荐做法（重写
 * {@code IItemExtension#canPerformAction}），用于钉住「活锄头判定不认物品清单、
 * 只认 ItemAbility」这条跨模组兼容契约。</p>
 *
 * <p>⚠️ 不能 {@code new Item(new Item.Properties())}：测试期物品注册表已冻结，
 * {@code Item} 构造里的 {@code createIntrusiveHolder} 会抛
 * {@code IllegalStateException: Registry is already frozen}。
 * 故改为 spy 一个真实已注册物品（木棍）并只改写 {@code canPerformAction}——
 * 其余行为（含 {@code components()}，ItemStack 构造要用）仍是真实的。</p>
 */
public final class FakeHoe {

    private FakeHoe() {}

    /** 返回一个「自实现工具类」形态的锄头：能 HOE_TILL，但完全不是 HoeItem */
    public static Item create() {
        Item hoe = Mockito.spy(Items.STICK);
        Mockito.doReturn(true).when(hoe)
            .canPerformAction(Mockito.any(ItemStack.class), Mockito.eq(ItemAbilities.HOE_TILL));
        return hoe;
    }
}
