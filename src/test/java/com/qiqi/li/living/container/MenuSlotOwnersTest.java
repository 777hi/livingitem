package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 「菜单槽 → 容器位置」解析守卫（契约 I-C5 / 专题 S3 / `D-container-09`）。
 *
 * <p>守两件事：</p>
 * <ol>
 *   <li><b>核心只提供机制，不猜第三方 API</b>：菜单即使自带 {@code getBlockPosition()}，
 *       核心也<b>不反射</b>——必须由联动模块显式注册才认。这是 `D-container-09` 的直接守卫。</li>
 *   <li><b>结构化解析优先且无反射</b>：原版 BE 槽位直接取位置，大箱子取两半。</li>
 * </ol>
 */
class MenuSlotOwnersTest {

    private static final BlockPos POS = new BlockPos(3, 70, -5);
    private static final BlockPos OTHER = new BlockPos(9, 70, 9);

    /** 仿模组容器：菜单自己知道自己是哪个方块。 */
    private static class PosAwareMenu extends AbstractContainerMenu {
        private final BlockPos pos;

        PosAwareMenu(BlockPos pos) {
            super(MenuType.GENERIC_9x3, 0);
            this.pos = pos;
        }

        /** 有这个方法，但核心<b>不该</b>反射它（只有联动模块注册后才认）。 */
        public BlockPos getBlockPosition() {
            return pos;
        }

        /** addSlot 是 protected —— 子类内转发一下，供测试挂槽。 */
        void append(Slot s) {
            addSlot(s);
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

    /** 不暴露任何位置方法的普通菜单。 */
    private static class PlainMenu extends AbstractContainerMenu {
        PlainMenu(Slot slot) {
            super(MenuType.GENERIC_9x3, 0);
            addSlot(slot);
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

    @Test
    @DisplayName("核心不反射：菜单自带 getBlockPosition 也不认（必须联动模块注册）")
    void coreDoesNotReflectThirdPartyMethod() {
        assertNull(MenuSlotOwners.menuPosition(new PosAwareMenu(POS)),
            "D-container-09：核心不得内置「反射第三方方法名」的兜底 —— "
            + "那等于把本模组绑在某个模组的 API 形状上");
    }

    @Test
    @DisplayName("注册联动解析器后即生效（机制与策略分离）")
    void registeredResolver_isHonored() {
        MenuSlotOwners.registerMenuResolver(
            menu -> menu instanceof CompatOnlyMenu ? POS : null);

        assertEquals(POS, MenuSlotOwners.menuPosition(new CompatOnlyMenu()));
    }

    /** 专用菜单类：注册解析器的测试只作用于它，避免污染同类的其它用例。 */
    private static class CompatOnlyMenu extends AbstractContainerMenu {
        CompatOnlyMenu() {
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

    @Test
    @DisplayName("菜单没有解析器 → null（不误报）")
    void menuPosition_returnsNullWhenAbsent() {
        assertNull(MenuSlotOwners.menuPosition(new PlainMenu(new Slot(new SimpleContainer(1), 0, 0, 0))));
    }

    /** 回落测试专用菜单类（避免与其它用例共享注册状态）。 */
    private static class FallbackMenu extends AbstractContainerMenu {
        FallbackMenu() {
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

    @Test
    @DisplayName("模组容器槽（非 BE、非 CompoundContainer）→ 回落到菜单级位置")
    void resolve_fallsBackToMenuPosition() {
        // ⚠️ 每个注册用例用<b>自己的菜单类</b>：注册表是全局静态的，共用类会互相污染
        MenuSlotOwners.registerMenuResolver(menu -> menu instanceof FallbackMenu ? POS : null);

        FallbackMenu menu = new FallbackMenu();
        Slot slot = new Slot(new SimpleContainer(9), 0, 0, 0);   // 槽的 container 与位置无关

        assertEquals(List.of(POS), MenuSlotOwners.resolve(menu, slot));
        assertTrue(MenuSlotOwners.isViewing(menu, List.of(POS)),
            "菜单整体属于 POS ⇒ 视为在看 POS");
        assertFalse(MenuSlotOwners.isViewing(menu, List.of(OTHER)),
            "不能把别的位置也算成在看");
    }

    @Test
    @DisplayName("原版方块实体槽 → 直接用 BE 位置（快路径，不依赖任何注册）")
    void resolve_usesBlockEntityPosition() {
        BlockEntity be = Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
        Mockito.when(be.getBlockPos()).thenReturn(POS);

        Slot slot = new Slot((Container) be, 0, 0, 0);
        PlainMenu menu = new PlainMenu(slot);   // 菜单没有任何位置来源也不影响

        assertEquals(List.of(POS), MenuSlotOwners.resolve(menu, slot));
        assertTrue(MenuSlotOwners.isViewing(menu, List.of(POS)));
    }

    @Test
    @DisplayName("大箱子：CompoundContainer → 两半的位置（防跨容器虚影）")
    void resolve_doubleChest_returnsBothHalves() {
        BlockEntity leftBe = Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
        BlockEntity rightBe = Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
        Mockito.when(leftBe.getBlockPos()).thenReturn(POS);
        Mockito.when(rightBe.getBlockPos()).thenReturn(OTHER);

        net.minecraft.world.CompoundContainer compound = new net.minecraft.world.CompoundContainer(
            (Container) leftBe, (Container) rightBe);

        // 槽的 container 必须是 CompoundContainer 本身（原版大箱菜单就是这么干的）
        PlainMenu menuContainer = new PlainMenu(new Slot(compound, 0, 0, 0));

        List<BlockPos> positions = MenuSlotOwners.resolve(menuContainer, menuContainer.slots.get(0));
        assertTrue(positions.size() == 2 && positions.contains(POS) && positions.contains(OTHER),
            "大箱应解析出两半的位置，实际：" + positions);
    }

    @Test
    @DisplayName("空位置集合 → 恒 false（宁可不发，也不发错人）")
    void isViewing_emptyPositions_returnsFalse() {
        assertFalse(MenuSlotOwners.isViewing(new PosAwareMenu(POS), List.of()));
        assertFalse(MenuSlotOwners.isViewing(null, List.of(POS)));
    }
}
