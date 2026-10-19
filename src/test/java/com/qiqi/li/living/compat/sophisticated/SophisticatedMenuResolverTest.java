package com.qiqi.li.living.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.test.FakeBackpackMenu;

/**
 * 精妙背包联动解析器的守卫（`D-container-09`）。
 *
 * <p>守两件事：</p>
 * <ol>
 *   <li><b>认得正路</b>：包名匹配 + 有 {@code getBlockPosition()} ⇒ 解出位置。</li>
 *   <li><b>反射不扩散</b>：包名不匹配的菜单即使<b>也有</b>同名方法，也一律不认 ——
 *       否则任何模组的方法都可能被误命中（例如返回玩家/合成格坐标 ⇒ 把包发错人）。</li>
 * </ol>
 */
class SophisticatedMenuResolverTest {

    private static final BlockPos POS = new BlockPos(11, 64, -7);

    private final SophisticatedMenuResolver resolver = new SophisticatedMenuResolver();

    @Test
    @DisplayName("包名匹配 + getBlockPosition() → 解出位置（精妙背包的正路）")
    void resolvesMatchingPackage() {
        assertEquals(POS, resolver.resolve(new FakeBackpackMenu(POS)));
    }

    @Test
    @DisplayName("包名不匹配 → 即使有同名方法也不认（反射不扩散）")
    void ignoresForeignPackageWithSameMethod() {
        assertNull(resolver.resolve(new LookalikeMenu()),
            "判据必须先卡包名前缀 —— 否则等于对全世界的菜单做反射猜测");
    }

    /** 包名不匹配、但同样有 {@code getBlockPosition()} 的菜单。 */
    private static class LookalikeMenu extends AbstractContainerMenu {
        LookalikeMenu() {
            super(MenuType.GENERIC_9x3, 0);
        }

        public java.util.Optional<BlockPos> getBlockPosition() {
            return java.util.Optional.of(POS);
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
