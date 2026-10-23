package net.p3pp3rf1y.sophisticatedbackpacks.common.gui;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 测试替身：精妙的<b>背包菜单</b>（2026-10-23）。
 *
 * <p>{@code SophisticatedBackpackSource#handleOfMenu} 的判据是
 * {@code BackpackContainer.isInstance(menu)}，随后读 {@link #getBackpackContext()}。
 * 替身必须真的叫这个名字、真的在那个包下 —— 否则只能测到「不认」，测不到正路。</p>
 *
 * <p>⚠️ 本类<b>不引用任何精妙背包的类</b>（只有自己的 {@link BackpackContext} 替身），
 * 所以不需要对方 jar。</p>
 */
public class BackpackContainer extends AbstractContainerMenu {

    private final BackpackContext context;

    public BackpackContainer(BackpackContext context) {
        super(MenuType.GENERIC_9x3, 0);
        this.context = context;
    }

    /** 真实 {@code BackpackContainer#getBackpackContext()} 的同形方法。 */
    public BackpackContext getBackpackContext() {
        return context;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
