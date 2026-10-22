package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.water.ContainerFluidData;
import com.qiqi.li.living.domain.water.ContainerStressData;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 物品形态容器的两块地基（2026-10-22，活背包）：
 * {@link ItemFormContainers}（来源注册口）与 {@link ItemFormContainerContext}（ctx 的
 * SavedData 覆写）。
 *
 * <p>最要紧的一条是 {@code persist} 覆写：不覆写就落到 default 的「遍历 hosts 写附件」，
 * 而物品形态 hosts 恒为空 ⇒ <b>数据静默不落盘</b>（LRU 回收即丢）。这个测试就是钉它。</p>
 */
class ItemFormContainerContextTest {

    /** 假来源：只认潜影盒（测试不引入第三方类型）。 */
    private static final class FakeSource implements ItemFormContainers.Source {
        private final IItemHandler handler;

        FakeSource(IItemHandler handler) {
            this.handler = handler;
        }

        @Override
        public boolean isContainerItem(ItemStack stack) {
            return stack.is(Items.SHULKER_BOX);
        }

        @Override
        public IItemHandler contentHandler(ItemStack stack) {
            return handler;
        }
    }

    private IItemHandler handler;

    @BeforeEach
    void setUp() {
        ItemFormContainers.clearForTest();
        handler = new ItemStackHandler(9);
    }

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
    }

    // ── 来源注册口 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("注册口：没注册来源 ⇒ 任何物品都不算容器")
    void noSource_meansNotContainer() {
        assertFalse(ItemFormContainers.isContainerItem(new ItemStack(Items.SHULKER_BOX)));
        assertNull(ItemFormContainers.find(new ItemStack(Items.SHULKER_BOX)));
    }

    @Test
    @DisplayName("注册口：注册后能认领，且 find 返回同一个来源")
    void registeredSource_isFound() {
        FakeSource source = new FakeSource(handler);
        ItemFormContainers.register(source);

        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        assertTrue(ItemFormContainers.isContainerItem(shulker));
        assertSame(source, ItemFormContainers.find(shulker));
        assertNull(ItemFormContainers.find(new ItemStack(Items.STONE)), "非容器物品不该被认领");
        assertNull(ItemFormContainers.find(ItemStack.EMPTY), "空栈不是容器");
    }

    // ── ctx 的身份与键 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ctx：容器 UUID 与 cuuid_ 键互逆")
    void context_exposesUuidAndKey() {
        UUID uuid = UUID.randomUUID();
        ItemFormContainerContext ctx = new ItemFormContainerContext(handler, uuid, null);

        assertEquals(uuid, ctx.getContainerUuid());
        assertEquals(ContainerKeys.cuuidKey(uuid), ctx.getContainerKey());
        assertEquals(ContainerKind.ITEM_FORM, ContainerKeys.kindOf(ctx.getContainerKey()));
        assertTrue(ctx.getDataHosts().isEmpty(), "物品栈不是附件宿主 ⇒ 宿主列表必须为空");
        assertNull(ctx.getBlockPos(), "物品形态没有世界位置");
    }

    // ── persist 覆写（本类的存在理由）───────────────────────────────────────

    @Test
    @DisplayName("persist：写进 SavedData（不是空转的 default）")
    void persist_writesToSavedData() {
        UUID uuid = UUID.randomUUID();
        ItemFormContainerContext ctx = new ItemFormContainerContext(handler, uuid, null);

        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        ctx.persist(ContainerFluidData.KEY, data);

        ContainerFluidData stored = ContainerDataSavedData.get().get(uuid, ContainerFluidData.KEY);
        assertNotNull(stored, "必须落进 SavedData —— default 实现会因为 hosts 为空而静默丢弃");
        assertEquals(1, stored.getGeneratedSources().size());
        assertEquals(Fluids.WATER.getFluidType(), stored.getGeneratedSources().get(3));
    }

    @Test
    @DisplayName("persist：空值 ⇒ 删条目（不是写一条空数据）")
    void persist_emptyValue_removesEntry() {
        UUID uuid = UUID.randomUUID();
        ItemFormContainerContext ctx = new ItemFormContainerContext(handler, uuid, null);

        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(3, Fluids.WATER.getFluidType());
        ctx.persist(ContainerFluidData.KEY, data);
        assertNotNull(ContainerDataSavedData.get().get(uuid, ContainerFluidData.KEY));

        ctx.persist(ContainerFluidData.KEY, ContainerFluidData.EMPTY);
        assertNull(ContainerDataSavedData.get().get(uuid, ContainerFluidData.KEY),
            "EMPTY 必须删条目，否则存档里堆空记录");
    }

    @Test
    @DisplayName("persist：非 isSaved 的 key ⇒ 不写（应力这类运行时数据）")
    void persist_unsavedKey_ignored() {
        UUID uuid = UUID.randomUUID();
        ItemFormContainerContext ctx = new ItemFormContainerContext(handler, uuid, null);

        ctx.persist(ContainerStressData.KEY, new ContainerStressData());

        assertEquals(0, ContainerDataSavedData.get().size(), "没有 CODEC 的 key 不该进 SavedData");
    }

    // ── 菜单反查（#6 菜单解析，2026-10-22）──────────────────────────────────
    //
    // tooltip / 倒汲 / GUI 槽位设置都走 ContainerContexts.resolve → handleOf。
    // 核心策略：**多件不猜**（菜单不告诉你对应第几件，猜错会操作到别的容器）。

    private static Player playerWith(ItemStack... items) {
        Player player = org.mockito.Mockito.mock(Player.class);
        net.minecraft.world.entity.player.Inventory inv =
            org.mockito.Mockito.mock(net.minecraft.world.entity.player.Inventory.class);
        org.mockito.Mockito.when(player.getInventory()).thenReturn(inv);
        org.mockito.Mockito.when(inv.getContainerSize()).thenReturn(items.length);
        for (int i = 0; i < items.length; i++) {
            org.mockito.Mockito.when(inv.getItem(i)).thenReturn(items[i]);
        }
        return player;
    }

    private static net.minecraft.world.inventory.AbstractContainerMenu menu() {
        return org.mockito.Mockito.mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
    }

    private static ItemStack livingBackpack() {
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        ContainerUuids.getOrCreateItem(stack);   // 只有活化的才有身份
        return stack;
    }

    @Test
    @DisplayName("菜单反查：物品栏没有活容器 ⇒ null")
    void handleOf_noLivingContainer_returnsNull() {
        ItemFormContainers.register(new FakeSource(handler));
        Player player = playerWith(new ItemStack(Items.SHULKER_BOX), ItemStack.EMPTY);   // 有容器但没活化

        assertNull(ItemFormContainers.handleOf(menu(), player));
    }

    @Test
    @DisplayName("菜单反查：唯一一件 ⇒ 认它（这是常态路径）")
    void handleOf_singleLivingContainer_returnsIt() {
        ItemFormContainers.register(new FakeSource(handler));
        ItemStack backpack = livingBackpack();
        Player player = playerWith(backpack, ItemStack.EMPTY, ItemStack.EMPTY);

        var handle = ItemFormContainers.handleOf(menu(), player);
        assertNotNull(handle, "唯一一件必须认 —— 否则 tooltip / 倒汲全失效");
        assertEquals(ContainerUuids.ofItem(backpack), handle.containerUuid());
    }

    @Test
    @DisplayName("菜单反查：两件同类容器 ⇒ null（不猜，猜错会操作到另一个容器）")
    void handleOf_multipleLivingContainers_refusesToGuess() {
        ItemFormContainers.register(new FakeSource(handler));
        Player player = playerWith(livingBackpack(), livingBackpack());

        assertNull(ItemFormContainers.handleOf(menu(), player),
            "菜单不告诉你对应第几件 ⇒ 必须返回 null 而不是随便挑一个");
    }

    @Test
    @DisplayName("菜单反查：未注册来源 ⇒ null（未装模组零影响）")
    void handleOf_withoutSource_returnsNull() {
        assertNull(ItemFormContainers.handleOf(menu(), playerWith(livingBackpack())));
    }
}
