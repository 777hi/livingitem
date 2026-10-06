package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * {@link ContainerContexts} 边界带共享内核的单元测试（Q6 批次 B，2026-10-04）。
 *
 * <p>钉住两个服务端入口：{@link ContainerContexts#ownsContainer}（菜单槽位容器归属，
 * 含大箱 {@code CompoundContainer} 特判）与 {@link ContainerContexts#isSameSlotSpace}
 * （Container ↔ handler 槽位体系一致性探针）。两者收编自
 * {@code SimpleContainerContext.slotBelongsTo} / {@code isSameSlotSpaceAsHandler}，
 * 行为必须与迁移前逐字一致。</p>
 */
class ContainerContextsTest {

    @Nested
    @DisplayName("ownsContainer —— 菜单槽位容器归属（含大箱 CompoundContainer 特判）")
    class OwnsContainer {

        @Test
        @DisplayName("单箱：菜单容器即 BE 本体，实例匹配命中")
        void singleChest_instanceMatch() {
            SimpleContainer be = new SimpleContainer(27);
            assertTrue(ContainerContexts.ownsContainer(be, Set.of(be)));
        }

        @Test
        @DisplayName("大箱：菜单容器是 CompoundContainer，用 contains(半箱) 匹配")
        void doubleChest_compoundContains() {
            SimpleContainer left = new SimpleContainer(27);
            SimpleContainer right = new SimpleContainer(27);
            CompoundContainer menu = new CompoundContainer(left, right);
            assertTrue(ContainerContexts.ownsContainer(menu, Set.of(left)));
            assertTrue(ContainerContexts.ownsContainer(menu, Set.of(right)));
        }

        @Test
        @DisplayName("大箱：CompoundContainer 不含集合中任何半箱 → false（防跨容器虚影）")
        void doubleChest_foreignContainer() {
            CompoundContainer menu = new CompoundContainer(new SimpleContainer(27), new SimpleContainer(27));
            SimpleContainer foreign = new SimpleContainer(27);
            assertFalse(ContainerContexts.ownsContainer(menu, Set.of(foreign)));
        }

        @Test
        @DisplayName("空集合 → false")
        void emptySet_false() {
            assertFalse(ContainerContexts.ownsContainer(new SimpleContainer(27), Set.of()));
        }
    }

    @Nested
    @DisplayName("isSameSlotSpace —— Container ↔ handler 槽位体系一致性探针")
    class IsSameSlotSpace {

        @Test
        @DisplayName("槽位数不一致（单体 27 vs 合并 54）→ false，调用方回退 handler")
        void sizeMismatch_false() {
            SimpleContainer container = new SimpleContainer(27);
            ItemStackHandler handler = new ItemStackHandler(54);
            assertFalse(ContainerContexts.isSameSlotSpace(container, handler, 0));
        }

        @Test
        @DisplayName("槽位越界 → false")
        void outOfRange_false() {
            SimpleContainer container = new SimpleContainer(27);
            ItemStackHandler handler = new ItemStackHandler(27);
            assertFalse(ContainerContexts.isSameSlotSpace(container, handler, 999));
            assertFalse(ContainerContexts.isSameSlotSpace(container, handler, -1));
        }

        @Test
        @DisplayName("两套体系同空 → true")
        void bothEmpty_true() {
            SimpleContainer container = new SimpleContainer(27);
            ItemStackHandler handler = new ItemStackHandler(27);
            assertTrue(ContainerContexts.isSameSlotSpace(container, handler, 3));
        }

        @Test
        @DisplayName("同一槽位物品一致 → true")
        void sameItem_true() {
            SimpleContainer container = new SimpleContainer(27);
            ItemStackHandler handler = new ItemStackHandler(27);
            container.setItem(3, new ItemStack(Items.STONE));
            handler.setStackInSlot(3, new ItemStack(Items.STONE));
            assertTrue(ContainerContexts.isSameSlotSpace(container, handler, 3));
        }

        @Test
        @DisplayName("一空一非空 → false（合并顺序相反的典型错位）")
        void oneEmpty_false() {
            SimpleContainer container = new SimpleContainer(27);
            ItemStackHandler handler = new ItemStackHandler(27);
            container.setItem(3, new ItemStack(Items.STONE));
            assertFalse(ContainerContexts.isSameSlotSpace(container, handler, 3));
        }

        @Test
        @DisplayName("同一槽位物品不同 → false")
        void differentItem_false() {
            SimpleContainer container = new SimpleContainer(27);
            ItemStackHandler handler = new ItemStackHandler(27);
            container.setItem(3, new ItemStack(Items.STONE));
            handler.setStackInSlot(3, new ItemStack(Items.DIRT));
            assertFalse(ContainerContexts.isSameSlotSpace(container, handler, 3));
        }
    }

    @Nested
    @DisplayName("resolve —— 菜单槽位 → tick 上下文（F-1 末影箱分支）")
    class Resolve {

        @Test
        @DisplayName("末影箱菜单槽位可解析：PlayerEnderChestContainer → EnderChestContainerContext")
        void enderChest_resolves() {
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(mock(Level.class));
            when(player.getStringUUID()).thenReturn("uuid-ec");

            // 原版末影箱 GUI 的槽位容器就是 PlayerEnderChestContainer（不是 BE）
            PlayerEnderChestContainer enderChest = new PlayerEnderChestContainer();
            when(player.getEnderChestInventory()).thenReturn(enderChest);
            Slot slot = new Slot(enderChest, 0, 0, 0);

            TickableContainerContext ctx = ContainerContexts.resolve(player, slot);
            assertNotNull(ctx, "末影箱菜单槽位应能解析（F-1 之前恒 null ⇒ 末影箱汲/倒静默无效）");
            assertEquals("player_uuid-ec_ender_chest", ctx.getContainerKey());
        }

        @Test
        @DisplayName("非容器槽位仍返回 null（不误判）")
        void unknownContainer_null() {
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(mock(Level.class));
            Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);

            assertNull(ContainerContexts.resolve(player, slot));
        }
    }

    @Nested
    @DisplayName("isViewingEnderChest —— 第 ③ 次泄漏修复的 viewer 判据（2026-10-06）")
    class IsViewingEnderChest {

        @Test
        @DisplayName("末影箱菜单 → true")
        void enderChestMenu_true() {
            ChestMenu menu = mock(ChestMenu.class);
            when(menu.getContainer()).thenReturn(new PlayerEnderChestContainer());

            assertTrue(ContainerContexts.isEnderChestMenu(menu));
        }

        @Test
        @DisplayName("ChestMenu 但容器是普通箱 → false（客户端同形，靠这一条区分）")
        void chestMenuWithPlainContainer_false() {
            ChestMenu menu = mock(ChestMenu.class);
            when(menu.getContainer()).thenReturn(new SimpleContainer(27));

            assertFalse(ContainerContexts.isEnderChestMenu(menu));
        }

        @Test
        @DisplayName("非 ChestMenu 菜单（如工作台/熔炉）→ false")
        void otherMenu_false() {
            assertFalse(ContainerContexts.isEnderChestMenu(mock(AbstractContainerMenu.class)));
        }

        @Test
        @DisplayName("null 菜单 → false（不抛）")
        void nullMenu_false() {
            assertFalse(ContainerContexts.isEnderChestMenu(null));
        }

        @Test
        @DisplayName("玩家侧入口：containerMenu 是末影箱菜单 → true")
        void playerEntry_true() {
            Player player = mock(Player.class);
            ChestMenu menu = mock(ChestMenu.class);
            when(menu.getContainer()).thenReturn(new PlayerEnderChestContainer());
            player.containerMenu = menu;

            assertTrue(ContainerContexts.isViewingEnderChest(player));
        }

        @Test
        @DisplayName("玩家侧入口：玩家正在看普通箱子 → false（这正是泄漏③的判别点）")
        void playerEntry_chestViewer_false() {
            Player player = mock(Player.class);
            ChestMenu menu = mock(ChestMenu.class);
            when(menu.getContainer()).thenReturn(new SimpleContainer(27));
            player.containerMenu = menu;

            assertFalse(ContainerContexts.isViewingEnderChest(player),
                "玩家看的是普通箱子 ⇒ 末影箱快照不得下发（否则串台）");
        }
    }
}
