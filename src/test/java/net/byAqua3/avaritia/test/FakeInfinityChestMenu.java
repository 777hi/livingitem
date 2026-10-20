package net.byAqua3.avaritia.test;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 无尽箱子菜单的<b>替身</b>（测试用）。
 *
 * <p>⚠️ 故意放在 {@code net.byAqua3.avaritia.test} 包下：{@code AvaritiaMenuResolver} 的
 * 第一道判据是<b>包名前缀</b>，放在别处只能测到「不认」，测不到「认得」。</p>
 *
 * <p>形状与真实 {@code MenuInfinityChest} 一致：{@code getTile()} 返回方块实体
 * （真实返回 {@code TileInfinityChest}）。</p>
 */
public class FakeInfinityChestMenu extends AbstractContainerMenu {

    private final BlockEntity tile;

    public FakeInfinityChestMenu(BlockEntity tile) {
        super(MenuType.GENERIC_9x3, 0);
        this.tile = tile;
    }

    public BlockEntity getTile() {
        return tile;
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
