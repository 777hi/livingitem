package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 实体容器（船 / 矿车 / 带箱驴骡）的上下文与菜单解析守卫（2026-10-21）。
 *
 * <p>设计稿：{@code docs/buffer/container-menu-introspection-topic.md} §2.2 / §3。
 * 覆盖四类容器：箱子船、箱子矿车、漏斗矿车（{@link ContainerEntity}）与带箱驴骡
 * （{@link AbstractChestedHorse#hasChest()}）。</p>
 *
 * <p>⚠️ <b>测不了的部分</b>：{@code HorseInventoryMenuAccessor} 是 Mixin，单测环境不应用
 * Mixin ⇒ {@code menu instanceof HorseInventoryMenuAccessor} 恒 false ⇒ 驴的菜单解析
 * （accessor 路径）与「普通马不当容器」这条只能真机验证 —— 已在测试留言里标注。</p>
 */
class EntityContainersTest {

    private static final BlockPos BOAT_POS = new BlockPos(11, 64, -7);
    private static final UUID ID = UUID.fromString("0f1c3b8e-7a2d-4e5f-9c6b-1d2e3f4a5b6c");

    /** 27 格的船替身（ContainerEntity extends Container ⇒ 能力注册面在）。 */
    private static Entity boatEntity(IItemHandler handler) {
        Entity boat = mock(Entity.class, org.mockito.Mockito.withSettings()
            .extraInterfaces(ContainerEntity.class, net.minecraft.world.Container.class));
        when(boat.getUUID()).thenReturn(ID);
        when(boat.blockPosition()).thenReturn(BOAT_POS);
        when(boat.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(handler);
        return boat;
    }

    private static EntityContainerContext boatContext(Entity boat) {
        return new EntityContainerContext(boat, mock(ServerLevel.class));
    }

    // ── 白名单 ──

    @Test
    @DisplayName("白名单：ContainerEntity（船/矿车）→ true；普通实体 → false")
    void whitelist_containerEntityOnly() {
        assertTrue(EntityContainers.isContainerEntity(boatEntity(new ItemStackHandler(27))),
            "箱子船/矿车（ContainerEntity）是真容器实体");

        assertFalse(EntityContainers.isContainerEntity(mock(Entity.class)),
            "普通实体（哪怕 NeoForge fallback 也给它注册了能力）不是容器 —— I-C1 张力");
    }

    @Test
    @DisplayName("白名单：带箱驴/骡 → true；无箱 → false（2 格只是鞍+体甲）")
    void whitelist_chestedHorseOnlyWhenHasChest() {
        AbstractChestedHorse donkey = mock(AbstractChestedHorse.class);
        when(donkey.hasChest()).thenReturn(true);
        assertTrue(EntityContainers.isContainerEntity(donkey));

        AbstractChestedHorse bare = mock(AbstractChestedHorse.class);
        when(bare.hasChest()).thenReturn(false);
        assertFalse(EntityContainers.isContainerEntity(bare),
            "无箱驴 inventory 只有 2 格（鞍+体甲），活马铠没有意义");

        assertFalse(EntityContainers.isContainerEntity(mock(AbstractHorse.class)),
            "普通马不在 AbstractChestedHorse 家族，压根不进本判据");
    }

    // ── 上下文 ──

    @Test
    @DisplayName("键形：entity_<uuid>（只依赖 UUID —— 实体移动不漂移）")
    void keyIsEntityUuid() {
        Entity boat = boatEntity(new ItemStackHandler(27));
        assertEquals("entity_" + ID, boatContext(boat).getContainerKey());
        assertEquals(ContainerKind.ENTITY, ContainerKeys.kindOf("entity_" + ID));
        assertEquals(ID, ContainerKeys.entityOf("entity_" + ID));
        assertNull(ContainerKeys.entityOf("chest_1_64_2"), "坐标键不是实体键");
        assertNull(ContainerKeys.entityOf("item_entity_" + ID), "掉落物键不是实体容器键");
    }

    @Test
    @DisplayName("列数：27 格船 → 启发式 9（Tier3；候选宽度顺序 9,10,12… 命中）")
    void widthFromHeuristic_chestBoat() {
        assertEquals(9, boatContext(boatEntity(new ItemStackHandler(27))).getWidth());
    }

    @Test
    @DisplayName("列数：5 格漏斗矿车 → 5；15 格带箱驴 → 5（启发式全对，无需求助规则表）")
    void widthFromHeuristic_hopperAndDonkey() {
        assertEquals(5, boatContext(boatEntity(new ItemStackHandler(5))).getWidth(),
            "漏斗矿车 5 格：9/10/12/13/8/7/6/11 都试过，落到 5");
        assertEquals(5, boatContext(boatEntity(new ItemStackHandler(15))).getWidth(),
            "带箱驴 15 格：落到 5（原版 getInventoryColumns() = 5，一致）");
    }

    @Test
    @DisplayName("getBlockPos() 恒 null：实体容器不是方块容器（防船停箱子上被信标误判）")
    void blockPosIsNull() {
        assertNull(boatContext(boatEntity(new ItemStackHandler(27))).getBlockPos(),
            "返回船位置会让 isLiddedChest(getBlockEntity(pos)) 拿到地形里的箱子 ⇒ 光束漂错地方");
    }

    @Test
    @DisplayName("数据宿主 = 实体本身（流体/相位附件挂实体，区块卸载再回来还在）")
    void dataHostIsEntity() {
        Entity boat = boatEntity(new ItemStackHandler(27));
        assertEquals(List.of(boat), boatContext(boat).getDataHosts());
    }

    @Test
    @DisplayName("无能力面的实体 → 构造即抛（I-C7：白名单与注册面不一致不得静默）")
    void missingCapability_throws() {
        Entity bare = mock(Entity.class);
        when(bare.getUUID()).thenReturn(ID);
        // getCapability 未 stub ⇒ NeoForge default 返回 null
        assertThrows(IllegalStateException.class,
            () -> boatContext(bare));
    }

    // ── 菜单解析（逐槽） ──

    @Test
    @DisplayName("菜单槽的 container 就是船本身（ChestBoat#createMenu 传 this）→ 直取实体")
    void resolveMenuEntity_structuredFromSlotContainer() {
        Entity boat = boatEntity(new ItemStackHandler(27));

        AbstractContainerMenu menu = new AbstractContainerMenu(MenuType.GENERIC_9x3, 0) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        };
        Slot slot = new Slot((net.minecraft.world.Container) boat, 3, 0, 0);
        menu.slots.add(slot);

        assertEquals(boat, EntityContainers.resolveMenuEntity(menu, slot),
            "船/矿车走结构化路径，零反射");
    }

    @Test
    @DisplayName("玩家背包槽 → null（不被实体容器的菜单级判定误并）")
    void resolveMenuEntity_playerInventorySlot_null() {
        AbstractContainerMenu menu = new AbstractContainerMenu(MenuType.GENERIC_9x3, 0) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        };
        Inventory inv = mock(Inventory.class);
        Slot slot = new Slot(inv, 3, 0, 0);
        menu.slots.add(slot);

        assertNull(EntityContainers.resolveMenuEntity(menu, slot),
            "背包 GUI 下方的 36 格不能变成「船的一部分」");
    }

    @Test
    @DisplayName("MenuSlotOwners.resolve（viewer 匹配口径）：船槽 → 实体【当前】位置")
    void menuSlotOwners_resolveEntitySlot() {
        Entity boat = boatEntity(new ItemStackHandler(27));

        AbstractContainerMenu menu = new AbstractContainerMenu(MenuType.GENERIC_9x3, 0) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        };
        menu.slots.add(new Slot((net.minecraft.world.Container) boat, 3, 0, 0));

        assertEquals(List.of(BOAT_POS), MenuSlotOwners.resolve(menu, menu.slots.get(0)),
            "位置口径的关键：resolve 无缓存、每次现算 —— 船漂走后立即拿到新位置");
    }

    @Test
    @DisplayName("注册表：注册的实体解析器产出实体，白名单外不过（普通马 hasChest=false ⇒ null）")
    void registeredResolver_filteredByWhitelist() {
        AbstractContainerMenu donkeyMenu = menuWithInventorySlot();
        AbstractContainerMenu horseMenu = menuWithInventorySlot();

        AbstractChestedHorse donkey = mock(AbstractChestedHorse.class);
        when(donkey.hasChest()).thenReturn(true);
        AbstractHorse plainHorse = mock(AbstractHorse.class);   // 无 hasChest ⇒ 不是容器实体

        MenuSlotOwners.registerEntityResolver(
            (m, s) -> m == donkeyMenu ? donkey : (m == horseMenu ? plainHorse : null));

        assertEquals(donkey, EntityContainers.resolveMenuEntity(donkeyMenu, donkeyMenu.slots.get(0)),
            "带箱驴骡：注册器认领 + 白名单放行");
        assertNull(EntityContainers.resolveMenuEntity(horseMenu, horseMenu.slots.get(0)),
            "普通马：注册器认领了，但白名单（hasChest）拦下 —— HorseInventoryMenu 是马/驴/骡共用类，"
                + "不过这道判据就会把「开马的 GUI」当成容器交互");
    }

    /** 只挂一个玩家背包槽的裸菜单（实体挂在菜单上，不在槽位上）。 */
    private static AbstractContainerMenu menuWithInventorySlot() {
        AbstractContainerMenu menu = new AbstractContainerMenu(MenuType.GENERIC_9x3, 0) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        };
        menu.slots.add(new Slot(mock(Inventory.class), 0, 0, 0));
        return menu;
    }

    @Test
    @DisplayName("反射判据有效：HorseInventoryMenu.horse 字段存在且类型是 AbstractHorse（入口层反射的前提）")
    void horseFieldReflection_isValid() {
        // 入口层解析器（LivingItem.HORSE_MENU_RESOLVER）反射这个字段；单测锁住它的形状
        // ⇒ 原版升级改了字段名/类型时，这里先红（而不是等真机「驴不显示」）
        var field = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            net.minecraft.world.inventory.HorseInventoryMenu.class.getDeclaredField("horse"));
        assertEquals(AbstractHorse.class, field.getType(),
            "字段类型决定调用方能否接住 —— 与 accessor 第一版返错类型同一坑");
    }

    @Test
    @DisplayName("hazard 存档：单测跑不了 accessor（Mixin 未应用）—— 驴的菜单解析靠真机")
    void horseAccessor_notActiveInUnitTest() {
        assertNotNull(EntityContainers.class, "占位：说明见类注释");
        // menu instanceof HorseInventoryMenuAccessor 在单测恒 false（接口无运行时实现）
        // ⇒「普通马不开箱也返回 null」这条单测天然通过但**没有证明力**，真机补验。
    }
}
