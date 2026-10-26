package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ItemFormContainers;

/**
 * 「随身携带」判据的守卫（2026-10-27）。
 *
 * <h3>守什么</h3>
 * <p>被动模式的辅助挖掘 / 补刀、远程可见性、背后环，问的都是「这个人身上有哪些活物品」。
 * 这个定义原来散成三份 {@code for (slot : player.getInventory())} —— 物品形态容器
 * （活背包）让它们一起失效：工具在背包里、背包在物品栏里，而工具不在顶层。</p>
 *
 * <h3>两个边界必须钉住</h3>
 * <ul>
 *   <li><b>未活化的容器不展开</b> —— 否则等于替玩家把没启用的背包算进战斗力，
 *       而且每 tick 多扫一遍内容（活化门槛即性能闸门）。</li>
 *   <li><b>只展开一层</b> —— 与 {@code MAX_DEPTH = 1} 一致，背包里的背包不参与。</li>
 * </ul>
 *
 * <p>⚠️ 写回也必须带位置：容器里的栈是 handler 的，就地扣耐久不一定生效 ⇒ 不写回
 * 就等于「白干活不掉耐久」。</p>
 */
class CarriedLivingItemsTest {

    /** 假来源：认领潜影盒，内容固定是同一个 handler（这样 writeBack 能验证到）。 */
    private static final class FakeSource implements ItemFormContainers.Source {
        final ItemStackHandler handler = new ItemStackHandler(9);

        @Override
        public boolean isContainerItem(ItemStack stack) {
            return stack.is(Items.SHULKER_BOX);
        }

        @Override
        public IItemHandler contentHandler(ItemStack stack) {
            return handler;
        }
    }

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
    }

    /** 服务端玩家替身 —— 只有 {@code ServerPlayer} 才走「直接展开容器内容」那条路。 */
    private static Player playerWith(Inventory inventory) {
        Player player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    @Test
    @DisplayName("顶层工具 ⇒ 收，innerSlot = -1（写回走物品栏）")
    void topLevelItem_collected() {
        Inventory inv = new Inventory(mock(Player.class));
        inv.setItem(10, new ItemStack(Items.DIAMOND_PICKAXE));
        Player player = playerWith(inv);

        List<CarriedLivingItems.Carried> got = CarriedLivingItems.of(player, s -> true, false);

        assertEquals(1, got.size());
        assertEquals(10, got.get(0).outerSlot());
        assertEquals(-1, got.get(0).innerSlot());
        assertFalse(got.get(0).inContainer());
    }

    @Test
    @DisplayName("已活化的容器 ⇒ 展开一层，innerSlot 指向容器内容格")
    void activatedContainer_expandsOneLayer() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        ItemStack backpack = new ItemStack(Items.SHULKER_BOX);
        ContainerUuids.getOrCreateItem(backpack);          // 已活化
        source.handler.setStackInSlot(3, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(10, backpack);
        Player player = playerWith(inv);

        List<CarriedLivingItems.Carried> got = CarriedLivingItems.of(player, s -> true, false);

        assertEquals(2, got.size(), "背包本身 + 它里面的工具");
        CarriedLivingItems.Carried inner = got.stream().filter(CarriedLivingItems.Carried::inContainer)
            .findFirst().orElseThrow();
        assertEquals(10, inner.outerSlot(), "宿主槽位 = 背包所在格");
        assertEquals(3, inner.innerSlot(), "内容槽位 = 容器里的第 3 格");
        assertTrue(inner.stack().is(Items.IRON_PICKAXE));
    }

    @Test
    @DisplayName("未活化的容器 ⇒ **不展开**（活化门槛即性能闸门，也不该替玩家算战斗力）")
    void unactivatedContainer_notExpanded() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        ItemStack backpack = new ItemStack(Items.SHULKER_BOX);   // 没有 CONTAINER_UUID
        source.handler.setStackInSlot(3, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(10, backpack);
        Player player = playerWith(inv);

        List<CarriedLivingItems.Carried> got = CarriedLivingItems.of(player, s -> true, false);

        assertEquals(1, got.size(), "只有背包本身，内容不展开");
        assertTrue(got.stream().noneMatch(CarriedLivingItems.Carried::inContainer));
    }

    @Test
    @DisplayName("skipMainHand ⇒ 主手那一格不收（原版已计入，再算一次会翻倍）")
    void skipMainHand() {
        Inventory inv = new Inventory(mock(Player.class));
        inv.selected = 5;
        inv.setItem(5, new ItemStack(Items.DIAMOND_PICKAXE));
        Player player = playerWith(inv);

        assertTrue(CarriedLivingItems.of(player, s -> true, true).isEmpty());
        assertEquals(1, CarriedLivingItems.of(player, s -> true, false).size());
    }

    /**
     * 主手是<b>活背包</b> ⇒ 背包本身不收，但**内容仍要展开**。
     *
     * <p>守的是 2026-10-27 审查抓到的两端不同源：原实现连容器展开一起跳，而客户端的
     * K2 条目没有「主手」概念、照算 ⇒ 手持背包挖矿时客户端偏快 ⇒ 服务端拒绝破坏
     * （「方块破坏又恢复」同症状）。原版计入的是手持工具，不是手持容器的内容。</p>
     */
    @Test
    @DisplayName("skipMainHand + 主手是背包 ⇒ 只跳背包本身，内容仍展开（否则两端不同源）")
    void skipMainHand_containerStillExpands() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        inv.selected = 5;
        ItemStack backpack = new ItemStack(Items.SHULKER_BOX);
        ContainerUuids.getOrCreateItem(backpack);
        source.handler.setStackInSlot(3, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(5, backpack);
        Player player = playerWith(inv);

        List<CarriedLivingItems.Carried> got = CarriedLivingItems.of(player, s -> true, true);

        assertEquals(1, got.size(), "只收背包里的工具，背包本身不收");
        assertTrue(got.get(0).inContainer());
        assertEquals(5, got.get(0).outerSlot(), "宿主槽位 = 主手那一格");
        assertEquals(3, got.get(0).innerSlot());
    }

    /**
     * 条目顺序 = 槽位号升序，容器条目紧跟其宿主槽位。
     *
     * <p>守的是 {@code E6}（掉落归属取 {@code helpers.get(0)} = "最靠前"的那把）：
     * 顺序一乱，附魔就归到错误的手上。容器条目在宿主之后、下一个顶层槽位之前。</p>
     */
    @Test
    @DisplayName("槽位顺序：容器条目紧跟宿主槽位，顶层按槽位号升序（E6 掉落归属取最靠前）")
    void of_returnsEntriesInSlotOrder() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        inv.setItem(3, new ItemStack(Items.IRON_PICKAXE));
        ItemStack backpack = new ItemStack(Items.SHULKER_BOX);
        ContainerUuids.getOrCreateItem(backpack);
        source.handler.setStackInSlot(2, new ItemStack(Items.IRON_SHOVEL));
        inv.setItem(10, backpack);
        inv.setItem(20, new ItemStack(Items.IRON_AXE));
        Player player = playerWith(inv);

        List<CarriedLivingItems.Carried> got = CarriedLivingItems.of(player,
            s -> !s.is(Items.SHULKER_BOX), false);

        assertEquals(3, got.size());
        assertEquals(3, got.get(0).outerSlot());
        assertEquals(-1, got.get(0).innerSlot());
        assertEquals(10, got.get(1).outerSlot(), "容器条目紧跟宿主槽位");
        assertEquals(2, got.get(1).innerSlot());
        assertEquals(20, got.get(2).outerSlot());
        assertEquals(-1, got.get(2).innerSlot());
    }

    /**
     * 写回前目标格内容已被换走 ⇒ 放弃（不得盲写）。
     *
     * <p>守的是 2026-10-27 审查指出的错格风险：回放（{@code LivingToolReplay}）会跑完整
     * 事件链，第三方回调若在此期间动过容器，盲写 {@code setStackInSlot} 会覆盖别人的物品。
     * 降级必须留痕（I-C6），但单测环境无日志断言，只钉「不覆盖 + 不抛」。</p>
     */
    @Test
    @DisplayName("写回时目标格内容已被换走 ⇒ 放弃（盲写会错写别的格子）")
    void writeBack_contentSwapped_givesUp() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        ItemStack backpack = new ItemStack(Items.SHULKER_BOX);
        ContainerUuids.getOrCreateItem(backpack);
        source.handler.setStackInSlot(3, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(10, backpack);
        Player player = playerWith(inv);

        CarriedLivingItems.Carried inner = CarriedLivingItems.of(player, s -> true, false).stream()
            .filter(CarriedLivingItems.Carried::inContainer).findFirst().orElseThrow();
        source.handler.setStackInSlot(3, new ItemStack(Items.DIRT));   // 回放期间被第三方换走

        ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(7);
        CarriedLivingItems.writeBack(player, inner, damaged);

        assertTrue(source.handler.getStackInSlot(3).is(Items.DIRT), "内容已变 ⇒ 不得覆盖");
    }

    @Test
    @DisplayName("写回槽位越界 ⇒ 放弃且不抛（脏 Carried 不许炸辅助链）")
    void writeBack_outOfBounds_givesUp() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        inv.setItem(10, new ItemStack(Items.SHULKER_BOX));
        Player player = playerWith(inv);

        CarriedLivingItems.Carried bogus = new CarriedLivingItems.Carried(
            new ItemStack(Items.IRON_PICKAXE), 10, 99, true);

        assertDoesNotThrow(() -> CarriedLivingItems.writeBack(
            player, bogus, new ItemStack(Items.IRON_PICKAXE)));
    }

    @Test
    @DisplayName("写回容器内容 ⇒ 走 setStackInSlot（不能写物品栏、不能退回 insertItem）")
    void writeBack_toContainer() {
        FakeSource source = new FakeSource();
        ItemFormContainers.register(source);

        Inventory inv = new Inventory(mock(Player.class));
        ItemStack backpack = new ItemStack(Items.SHULKER_BOX);
        ContainerUuids.getOrCreateItem(backpack);
        source.handler.setStackInSlot(3, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(10, backpack);
        Player player = playerWith(inv);

        CarriedLivingItems.Carried inner = CarriedLivingItems.of(player, s -> true, false).stream()
            .filter(CarriedLivingItems.Carried::inContainer).findFirst().orElseThrow();

        ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(7);
        CarriedLivingItems.writeBack(player, inner, damaged);

        assertEquals(7, source.handler.getStackInSlot(3).getDamageValue(),
            "容器内容必须被写回（否则背包里的工具白干活不掉耐久）");
        assertTrue(inv.getItem(10).is(Items.SHULKER_BOX), "物品栏那一格仍是背包本身");
    }

    /**
     * 客户端从 S2C 补的条目 ⇒ **仍然算「容器内」**。
     *
     * <p>守的是 2026-10-27 真机那个「方块破坏又恢复」：分层判据曾写成 {@code innerSlot >= 0}，
     * 而客户端补的条目拿不到位置（两个槽位都是 -1）⇒ 被判成"物品栏顶层" ⇒ 参与相加
     * ⇒ 客户端比服务端快一倍（日志：客户端 bonus=16 / 服务端 bonus=8）⇒ 服务端拒绝破坏。</p>
     *
     * <p>⇒ 「在不在容器里」必须是<b>显式字段</b>，不能从槽位推断。</p>
     */
    @Test
    @DisplayName("客户端补的条目（槽位未知）⇒ 仍算容器内，但不可写回")
    void s2cEntry_isContainerButNotWritable() {
        CarriedLivingItems.Carried fromS2c =
            new CarriedLivingItems.Carried(new ItemStack(Items.IRON_SHOVEL), -1, -1, true);

        assertTrue(fromS2c.inContainer(), "必须参与「背包只取最高」那条分层，否则两端速度不一致");
        assertFalse(fromS2c.canWriteBack(), "没有位置 ⇒ 不可写回（扣耐久只在服务端发生）");
    }
}
