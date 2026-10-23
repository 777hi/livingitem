package net.p3pp3rf1y.sophisticatedbackpacks.backpack;

import net.minecraft.world.item.Item;

/**
 * 测试替身：精妙背包<b>物品</b>（2026-10-23）。
 *
 * <p>{@code SophisticatedBackpackSource} 的反射解析第一件事就是
 * {@code Class.forName("...BackpackItem")} ⇒ <b>这个类必须存在</b>，否则整条链
 * （含菜单反查）全部判失败。它同时是 {@code isContainerItem} 判据的类。</p>
 *
 * <p>⚠️ <b>不要 {@code new} 它</b>：{@code Item} 构造器要写注册表，而测试里注册表已冻结
 * （{@code Registry is already frozen}）。{@code Class.forName} 只加载不实例化，
 * 所以判据类存在即可 —— 需要「一个背包物品栈」时用 {@code Items.SHULKER_BOX} 之类
 * 已注册物品充当（菜单反查那条链里不含物品类型判断）。</p>
 */
public class BackpackItem extends Item {

    public BackpackItem() {
        super(new Item.Properties());
    }
}
