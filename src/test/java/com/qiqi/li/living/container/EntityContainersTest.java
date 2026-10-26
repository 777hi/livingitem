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
import net.minecraft.world.item.Items;
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
 * <p>真实无类型马菜单的入口回归见 {@code ContainerMenuInfosTest}；实体反查复用入口层注册器。</p>
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

    /**
     * 带箱驴替身：handler 是 <b>16 槽</b>（原版布局：索引 0 = 马鞍、1..15 = 箱子格）。
     *
     * <p>⚠️ 真机因果链：16 槽不排除鞍 ⇒ 启发式 {@code 16 % 8 = 0 ⇒ 8 列}（实测「8×2」），
     * 且鞍槽被当普通格 ⇒ 幽灵物品。见 {@code EntityContainerContext.SADDLE_OFFSET}。</p>
     */
    private static Entity donkeyEntity() {
        AbstractHorse donkey = mock(AbstractChestedHorse.class);
        when(donkey.getUUID()).thenReturn(ID);
        when(donkey.blockPosition()).thenReturn(BOAT_POS);
        when(donkey.getCapability(Capabilities.ItemHandler.ENTITY))
            .thenReturn(new ItemStackHandler(16));
        when(donkey.getInventoryColumns()).thenReturn(5);   // 原版权威：hasChest() ? 5 : 0
        return donkey;
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
        assertEquals(15, boatContext(donkeyEntity()).getSize(),
            "带箱驴 16 槽去掉鞍槽 = 15 存储格（GUI 5×3）");
        assertEquals(5, boatContext(donkeyEntity()).getWidth(),
            "列数问原版 API（getInventoryColumns() = 5）—— 启发式对 16 槽会算 8（16%8=0），这是真机实测的错");
    }

    @Test
    @DisplayName("列数：原版答 0（箱子被摘掉 / 无箱）⇒ 不得返回 0 —— 列数是除数，0 会崩服务端")
    void width_neverZero_whenVanillaAnswersZero() {
        // 2026-10-24 真机崩服现场：带箱驴在函数阶段被自己的活 TNT 炸死
        // ⇒ dropEquipment() 摘掉箱子 ⇒ 原版 getInventoryColumns() 变 0
        // ⇒ 阶段 3（容器级数据）拿它当除数 ⇒ ArithmeticException / by zero
        AbstractHorse chestless = mock(AbstractChestedHorse.class);
        when(chestless.getUUID()).thenReturn(ID);
        when(chestless.blockPosition()).thenReturn(BOAT_POS);
        when(chestless.getCapability(Capabilities.ItemHandler.ENTITY))
            .thenReturn(new ItemStackHandler(1));      // 箱子没了，只剩鞍槽
        when(chestless.getInventoryColumns()).thenReturn(0);

        EntityContainerContext ctx = boatContext(chestless);

        assertTrue(ctx.getWidth() >= 1,
            "getWidth() 是网格除数（红石 / 流体 / 应力 / 信标列扫描都拿它做除法和取模）"
            + " —— 返回 0 会让整个服务端 / by zero");
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.FIXED), ctx.getLayout(),
            "摘箱后空存储区退化为明确的单列，不需要猜布局");
        assertEquals(0, ctx.getSize(), "无箱时存储格为 0（1 槽 - 鞍槽偏移）");
    }

    @Test
    @DisplayName("getBlockPos() 恒 null：实体容器不是方块容器（防船停箱子上被信标误判）")
    void blockPosIsNull() {
        assertNull(boatContext(boatEntity(new ItemStackHandler(27))).getBlockPos(),
            "返回船位置会让 isLiddedChest(getBlockEntity(pos)) 拿到地形里的箱子 ⇒ 光束漂错地方");
    }

    @Test
    @DisplayName("鞍槽排除与偏移映射：getItem(0) 读 handler 槽 1，鞍槽（handler 槽 0）永不被当存储格")
    void saddleSlotExcluded_andOffsetMapping() {
        IItemHandler handler = new ItemStackHandler(16);
        // ItemStackHandler 的 insertItem 尊重 slot 参数 ⇒ 用它精确预填
        handler.insertItem(0, Items.SADDLE.getDefaultInstance(), false);   // 鞍
        handler.insertItem(1, Items.APPLE.getDefaultInstance(), false);    // 第一个存储格
        AbstractHorse donkey = mock(AbstractChestedHorse.class);
        when(donkey.getUUID()).thenReturn(ID);
        when(donkey.blockPosition()).thenReturn(BOAT_POS);
        when(donkey.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(handler);
        when(donkey.getInventoryColumns()).thenReturn(5);

        var ctx = boatContext(donkey);

        assertEquals(Items.APPLE, ctx.getItem(0).getItem(),
            "逻辑槽 0 必须映射到 handler 槽 1（鞍槽 +1 偏移），否则整个网格偏一位");
        assertEquals(Items.SADDLE, handler.getStackInSlot(0).getItem(), "前置：handler 槽 0 是鞍");

        // 写也不能落进鞍槽：写逻辑槽 0 ⇒ handler 槽 1 变、槽 0 还是鞍
        ctx.setItem(0, Items.DIAMOND.getDefaultInstance());
        assertEquals(Items.DIAMOND, handler.getStackInSlot(1).getItem());
        assertEquals(Items.SADDLE, handler.getStackInSlot(0).getItem(),
            "写存储绝不能碰鞍槽 —— 否则玩家得到取不出的幽灵物品（GUI 只渲染鞍）");
    }

    @Test
    @DisplayName("船无装备槽：offset = 0，27 槽直通（不因驴的修复而变）")
    void boatHasNoOffset() {
        var ctx = boatContext(boatEntity(new ItemStackHandler(27)));
        assertEquals(27, ctx.getSize());
        assertEquals(9, ctx.getWidth(), "船仍走启发式（27 % 9 = 0）");
    }

    @Test
    @DisplayName("逻辑↔物理槽映射：驴 +1/-1 互逆；船恒等（真机「差一格」的根）")
    void slotMapping_isInverse() {
        var donkey = boatContext(donkeyEntity());
        assertEquals(1, donkey.toPhysicalSlot(0), "逻辑槽 0 → handler 槽 1（跳过马鞍）");
        assertEquals(0, donkey.toLogicalSlot(1), "物理槽 1 → 逻辑槽 0");
        for (int i = 0; i < donkey.getSize(); i++) {
            assertEquals(i, donkey.toLogicalSlot(donkey.toPhysicalSlot(i)),
                "两个方向必须互逆（否则同步与交互各偏一次）");
        }

        var boat = boatContext(boatEntity(new ItemStackHandler(27)));
        assertEquals(7, boat.toPhysicalSlot(7), "船无装备槽 ⇒ 恒等");
        assertEquals(7, boat.toLogicalSlot(7));
    }

    @Test
    @DisplayName("运行时遥测键用【物理槽】：驴的逻辑槽 0 写进 key 1（客户端按 getContainerSlot 查）")
    void runtimeTelemetryKeyedByPhysicalSlot() {
        var donkey = boatContext(donkeyEntity());
        com.qiqi.li.living.runtime.ContainerRuntimeCache.clear();

        com.qiqi.li.living.runtime.ContainerRuntimeCache.update(donkey, 0,
            com.qiqi.li.living.runtime.RuntimeSegments.EMPTY);

        String key = donkey.getCacheKey();
        var snapshot = com.qiqi.li.living.runtime.ContainerRuntimeCache.getSnapshot(key);
        assertTrue(snapshot.containsKey(1),
            "客户端悬停 GUI 第一个箱子格时 getContainerSlot() = 1 ⇒ 键必须是 1");
        assertFalse(snapshot.containsKey(0),
            "键 0 是马鞍槽，不该有遥测数据（否则悬停马鞍会显示第一个箱子的 tooltip）");
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

    @Test
    @DisplayName("原版箱船和两种容器矿车具有明确固定布局")
    void vanillaVehicles_useFixedLayouts() {
        for (var type : List.of(net.minecraft.world.entity.EntityType.CHEST_BOAT,
                net.minecraft.world.entity.EntityType.CHEST_MINECART,
                net.minecraft.world.entity.EntityType.HOPPER_MINECART)) {
            int columns = type == net.minecraft.world.entity.EntityType.HOPPER_MINECART ? 5 : 9;
            int size = columns == 5 ? 5 : 27;
            var entity = boatEntity(new ItemStackHandler(size));
            org.mockito.Mockito.doReturn(type).when(entity).getType();
            var ctx = boatContext(entity);
            assertEquals(new ContainerLayout(columns, ContainerLayout.Source.FIXED), ctx.getLayout());
            assertEquals(columns, ctx.getWidth());
        }
    }

    @Test
    @DisplayName("未知实体不因容量相同而获得原版固定布局")
    void foreignVehicle_remainsHeuristic() {
        var entity = boatEntity(new ItemStackHandler(27));
        org.mockito.Mockito.doReturn(net.minecraft.world.entity.EntityType.BOAT).when(entity).getType();
        assertEquals(new ContainerLayout(9, ContainerLayout.Source.HEURISTIC), boatContext(entity).getLayout());
    }

    @Test
    @DisplayName("同一原版实体类型被改变容量时不套用固定形状")
    void resizedVehicle_doesNotBorrowVanillaLayout() {
        var entity = boatEntity(new ItemStackHandler(30));
        org.mockito.Mockito.doReturn(net.minecraft.world.entity.EntityType.CHEST_MINECART).when(entity).getType();
        assertEquals(new ContainerLayout(10, ContainerLayout.Source.HEURISTIC), boatContext(entity).getLayout());
    }

    @Test
    @DisplayName("带箱羊驼的列数由强度API给出，不固定为5")
    void llama_usesLiveColumns() {
        var llama = mock(net.minecraft.world.entity.animal.horse.Llama.class);
        when(llama.getUUID()).thenReturn(ID);
        when(llama.blockPosition()).thenReturn(BOAT_POS);
        when(llama.getInventoryColumns()).thenReturn(3);
        when(llama.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(new ItemStackHandler(10));
        var ctx = boatContext(llama);
        assertEquals(9, ctx.getSize());
        assertEquals(new ContainerLayout(3, ContainerLayout.Source.LIVE), ctx.getLayout());
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


    // ════════════════════════════════════════
    // 容量/校验/模拟三通道过槽位映射（2026-10-27 审查 P2-1，I-C4/I-C10）
    // ════════════════════════════════════════

    /** 鞍槽禁插 + 首槽限流的自定义 handler —— 让「查物理 0」与「查物理 1」可判别。 */
    private static net.neoforged.neoforge.items.IItemHandlerModifiable discriminatingHandler() {
        return new net.neoforged.neoforge.items.ItemStackHandler(16) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot != 0;   // 物理 0（马鞍）禁插
            }

            @Override
            public int getSlotLimit(int slot) {
                return slot == 0 ? 1 : 64;   // 物理 0 限 1
            }
        };
    }

    private static com.qiqi.li.living.container.EntityContainerContext donkeyCtx(
            net.neoforged.neoforge.items.IItemHandlerModifiable handler) {
        AbstractHorse donkey = mock(AbstractChestedHorse.class);
        when(donkey.getUUID()).thenReturn(ID);
        when(donkey.blockPosition()).thenReturn(BOAT_POS);
        when(donkey.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY))
            .thenReturn(handler);
        when(donkey.getInventoryColumns()).thenReturn(5);
        return new com.qiqi.li.living.container.EntityContainerContext(donkey, mock(ServerLevel.class));
    }

    @Test
    @DisplayName("simulateInsert(逻辑0) ⇒ 必须查物理 1（旧实现查物理 0 ⇒ 鞍了鞍的驴第一箱格永远写不进）")
    void simulateInsert_mapsLogicalToPhysical() {
        var handler = discriminatingHandler();
        handler.setStackInSlot(0, new ItemStack(Items.STONE, 1));   // 物理 0（鞍位）被占
        var ctx = donkeyCtx(handler);

        int space = ctx.simulateInsertItem(0, new ItemStack(Items.DIRT, 64));

        assertEquals(64, space,
            "逻辑 0（第一箱子格，物理 1 空）应可放 64 —— 查到物理 0 的石头会返回 0");
    }

    @Test
    @DisplayName("isItemValid/getSlotLimit(逻辑0) ⇒ 过映射查物理 1（鞍位禁插/限流不得误伤第一箱格）")
    void isItemValidAndSlotLimit_mapLogicalToPhysical() {
        var ctx = donkeyCtx(discriminatingHandler());

        org.junit.jupiter.api.Assertions.assertTrue(ctx.isItemValid(0, new ItemStack(Items.DIRT)),
            "逻辑 0 → 物理 1 允许；查到物理 0 会因鞍位禁插而 false");
        assertEquals(64, ctx.getSlotLimit(0),
            "逻辑 0 → 物理 1 限 64；查到物理 0 会得到鞍位的 1");
    }
}
