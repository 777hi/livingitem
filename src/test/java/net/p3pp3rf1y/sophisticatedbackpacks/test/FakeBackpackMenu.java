package net.p3pp3rf1y.sophisticatedbackpacks.test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 测试替身：<b>故意放在精妙背包的包名下</b>（2026-10-19）。
 *
 * <p>{@code SophisticatedMenuResolver} 的判据是「包名前缀匹配 + 有 {@code getBlockPosition()}」。
 * 要验证正路，替身必须真的落在那个包下 —— 否则只能测到「不认」，测不到「认得」。</p>
 *
 * <p>⚠️ 本类<b>不引用任何精妙背包的类</b>，只是一个长得像的菜单，所以不需要对方 jar。</p>
 */
public class FakeBackpackMenu extends AbstractContainerMenu {

    private final BlockPos pos;

    public FakeBackpackMenu(BlockPos pos) {
        super(MenuType.GENERIC_9x3, 0);
        this.pos = pos;
    }

    /** 精妙背包 {@code BackpackContainer#getBlockPosition()} 的同形方法。 */
    public java.util.Optional<BlockPos> getBlockPosition() {
        return java.util.Optional.of(pos);
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
