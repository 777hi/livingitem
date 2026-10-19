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
 * 「菜单槽 → 容器位置」解析守卫（契约 I-C5 / 专题 S3）。
 *
 * <p>守的是<b>非原版 {@code Container} 的模组容器也能匹配 viewer</b>：
 * NeoForge 的 {@code SlotItemHandler} 内部 {@code container} 是<b>每槽一个匿名实例</b>，
 * 与任何东西都比不上 ⇒ 必须问<b>菜单</b>本身（精妙背包的
 * {@code BackpackContainer#getBlockPosition()} 就是这条路）。</p>
 */
class MenuSlotOwnersTest {

    private static final BlockPos POS = new BlockPos(3, 70, -5);
    private static final BlockPos OTHER = new BlockPos(9, 70, 9);

    /** 仿精妙背包：菜单自己知道自己是哪个方块。 */
    private static class PosAwareMenu extends AbstractContainerMenu {
        private final BlockPos pos;

        PosAwareMenu(BlockPos pos) {
            super(MenuType.GENERIC_9x3, 0);
            this.pos = pos;
        }

        /** 反射应命中这个方法（返回类型 BlockPos，名字在候选列表里）。 */
        public BlockPos getBlockPosition() {
            return pos;
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

    /** 不暴露位置的普通菜单（反射三条路都不命中）。 */
    private static class PlainMenu extends AbstractContainerMenu {
        PlainMenu(Slot slot) {
            super(MenuType.GENERIC_9x3, 0);
            addSlot(slot);   // ⚠️ 必须真的挂进 menu.slots —— 慢路径是遍历菜单槽位的
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
    @DisplayName("菜单级位置：反射命中 getBlockPosition()（精妙背包那条路）")
    void menuPosition_resolvesModdedMenu() {
        PosAwareMenu menu = new PosAwareMenu(POS);
        assertEquals(POS, MenuSlotOwners.menuPosition(menu));
    }

    /** 只有 getPos()（泛名字）—— 刻意不被采用，防误命中。 */
    private static class PosOnlyMenu extends AbstractContainerMenu {
        private final BlockPos pos;

        PosOnlyMenu(BlockPos pos) {
            super(MenuType.GENERIC_9x3, 0);
            this.pos = pos;
        }

        public BlockPos getPos() {
            return pos;
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
    @DisplayName("只暴露 getPos() → 不采用（泛名字误命中风险高于收益，compat 应用 registerMenuResolver）")
    void menuPosition_ignoresGenericGetPos() {
        assertNull(MenuSlotOwners.menuPosition(new PosOnlyMenu(POS)),
            "getPos 太泛（可能是玩家/合成格坐标），刻意不认");
    }

    /** 专用菜单类：注册解析器的测试只作用于它，避免污染同类的其它用例。 */
    private static class CompatMenu extends AbstractContainerMenu {
        CompatMenu() {
            super(MenuType.GENERIC_9x3, 0);
        }

        /** 反射本会命中它并返回 POS —— 用来证明注册的解析器优先级更高。 */
        public BlockPos getBlockPosition() {
            return POS;
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
    @DisplayName("注册的 compat 解析器优先于反射（编译期安全的正解）")
    void registeredResolver_winsOverReflection() {
        MenuSlotOwners.registerMenuResolver(menu ->
            menu instanceof CompatMenu ? OTHER : null);

        assertEquals(OTHER, MenuSlotOwners.menuPosition(new CompatMenu()),
            "注册的解析器应压过反射（getBlockPosition 返回 POS）");
    }

    @Test
    @DisplayName("菜单没有位置方法 → null（不误报）")
    void menuPosition_returnsNullWhenAbsent() {
        assertNull(MenuSlotOwners.menuPosition(new PlainMenu(new Slot(new SimpleContainer(1), 0, 0, 0))));
    }

    @Test
    @DisplayName("模组容器槽（非 BE、非 CompoundContainer）→ 回落到菜单级位置")
    void resolve_fallsBackToMenuPosition() {
        PosAwareMenu menu = new PosAwareMenu(POS);
        Slot slot = new Slot(new SimpleContainer(9), 0, 0, 0);   // 槽的 container 与位置无关

        assertEquals(List.of(POS), MenuSlotOwners.resolve(menu, slot));
        assertTrue(MenuSlotOwners.isViewing(menu, List.of(POS)),
            "菜单整体属于 POS ⇒ 视为在看 POS");
        assertFalse(MenuSlotOwners.isViewing(menu, List.of(OTHER)),
            "不能把别的位置也算成在看");
    }

    @Test
    @DisplayName("原版方块实体槽 → 直接用 BE 位置（快路径，不依赖菜单反射）")
    void resolve_usesBlockEntityPosition() {
        BlockEntity be = Mockito.mock(BlockEntity.class,
            Mockito.withSettings().extraInterfaces(Container.class));
        Mockito.when(be.getBlockPos()).thenReturn(POS);

        Slot slot = new Slot((Container) be, 0, 0, 0);
        PlainMenu menu = new PlainMenu(slot);   // 菜单没有位置方法也不影响

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

        net.minecraft.world.CompoundContainer menu = new net.minecraft.world.CompoundContainer(
            (Container) leftBe, (Container) rightBe);

        // 槽的 container 必须是 CompoundContainer 本身（原版大箱菜单就是这么干的）
        PlainMenu menuContainer = new PlainMenu(new Slot(menu, 0, 0, 0));

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
