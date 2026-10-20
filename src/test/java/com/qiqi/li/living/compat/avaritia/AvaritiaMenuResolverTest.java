package com.qiqi.li.living.compat.avaritia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.byAqua3.avaritia.test.FakeInfinityChestMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AvaritiaMenuResolver} 的判据守卫（`D-container-09`）。
 *
 * <p>无尽箱子的方块实体<b>不实现</b> {@code Container}、菜单也<b>没有</b>
 * {@code getBlockPosition()} ⇒ 核心的三条结构化路径全断。本模块靠「返回方块实体的 getter」
 * 补上这条链；这里钉住三件事：</p>
 * <ol>
 *   <li>认得正路（包名匹配 + {@code getTile()} 返回 BE）；</li>
 *   <li>包名不匹配时<b>即使有同名方法也不认</b>（反射不扩散）；</li>
 *   <li>返回类型不是 BE 时<b>不认</b>（不靠方法名猜语义）。</li>
 * </ol>
 */
class AvaritiaMenuResolverTest {

    private static final BlockPos POS = new BlockPos(11, 64, -7);

    private final AvaritiaMenuResolver resolver = new AvaritiaMenuResolver();

    @Test
    @DisplayName("包名匹配 + getTile() 返回方块实体 → 解出位置")
    void matchingPackage_returnsTilePosition() {
        BlockEntity tile = mock(BlockEntity.class);
        when(tile.getBlockPos()).thenReturn(POS);

        assertEquals(POS, resolver.resolve(new FakeInfinityChestMenu(tile)));
    }

    @Test
    @DisplayName("包名不匹配 → 即使有同样的 getTile() 也不认（反射不扩散到任意第三方）")
    void foreignPackage_isIgnored() {
        BlockEntity tile = mock(BlockEntity.class);
        when(tile.getBlockPos()).thenReturn(POS);

        // 方法名和返回类型都对，只有包名不对 —— 必须拒绝
        assertNull(resolver.resolve(new ForeignMenu(tile)),
            "非 avaritia 包的菜单不该被这个联动模块处理");
    }

    @Test
    @DisplayName("getTile() 返回的不是方块实体 → 不认（返回类型即判据，不靠方法名猜语义）")
    void nonBlockEntityReturn_isIgnored() {
        assertNull(resolver.resolve(new WrongReturnMenu()),
            "getTile 返回 Object 时不能当成方块实体 —— 否则可能把包发给错的人");
    }

    @Test
    @DisplayName("没有可用 getter → null（不抛）")
    void noGetter_returnsNull() {
        assertNull(resolver.resolve(new NoGetterMenu()));
    }

    // ── 替身 ────────────────────────────────────────────────

    /** 包名不对：方法形状完全正确，只有包前缀不匹配。 */
    private static class ForeignMenu extends AbstractContainerMenu {
        private final BlockEntity tile;

        ForeignMenu(BlockEntity tile) {
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

    /** 方法名对、返回类型不对。 */
    private static class WrongReturnMenu extends AbstractContainerMenu {
        WrongReturnMenu() {
            super(MenuType.GENERIC_9x3, 0);
        }

        public Object getTile() {
            return new Object();
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

    /** 什么都没有。 */
    private static class NoGetterMenu extends AbstractContainerMenu {
        NoGetterMenu() {
            super(MenuType.GENERIC_9x3, 0);
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
}
