package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.qiqi.li.living.transfer.ContainerCompatibilityConfig;

class ContainerMenuInfosTest {
    private static final ResourceLocation ID = ResourceLocation.withDefaultNamespace("furnace");
    private ServerPlayer player;
    private Level level;
    private Inventory inventory;

    static class Menu extends AbstractContainerMenu {
        Menu(MenuType<?> type, int id) { super(type, id); }
        void add(Slot slot) { addSlot(slot); }
        public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
    }

    @BeforeEach void before() {
        player = mock(ServerPlayer.class);
        level = mock(Level.class);
        inventory = mock(Inventory.class);
        when(player.level()).thenReturn(level);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getStringUUID()).thenReturn("test-player");
        ContainerCompatibilityConfig.remove(ID);
        LearnedLayouts.clearForTest();
    }
    @AfterEach void after() {
        LearnedLayouts.clearForTest();
        ContainerCompatibilityConfig.remove(ID);
        ItemFormContainers.clearForTest();
    }

    private FurnaceBlockEntity target(Menu menu) {
        var be = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        be.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(be);
        when(level.getCapability(Capabilities.ItemHandler.BLOCK, BlockPos.ZERO, null))
            .thenReturn(new ItemStackHandler(3));
        menu.add(new Slot(be, 0, 0, 0));
        player.containerMenu = menu;
        return be;
    }

    @Test void emptyBlockContainer_hasAuthoritativeInfoWithoutLivingItems() {
        var menu = new Menu(MenuType.FURNACE, 1);
        target(menu);
        var info = ContainerMenuInfos.resolve(player);
        assertEquals(ContainerMenuInfo.Status.AVAILABLE, info.status());
        assertEquals(ContainerKind.WORLD_BLOCK, info.kind());
        assertEquals(3, info.size());
        assertEquals(new ContainerLayout(1, ContainerLayout.Source.FAMILY), info.layout());
        assertEquals("minecraft:furnace", info.typeId());
    }

    @Test void inventoryMenu_queriesPlayerContext() throws Exception {
        var inventoryMenu = new net.minecraft.world.inventory.InventoryMenu(inventory, false, player);
        var field = net.minecraft.world.entity.player.Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(player, inventoryMenu);
        player.containerMenu = inventoryMenu;
        var owner = Inventory.class.getDeclaredField("player");
        owner.setAccessible(true);
        owner.set(inventory, player);
        when(player.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(new ItemStackHandler(41));
        var info = ContainerMenuInfos.resolve(player);
        assertEquals(ContainerMenuInfo.Status.AVAILABLE, info.status());
        assertEquals(ContainerKind.PLAYER_INV, info.kind());
        assertEquals(new ContainerLayout(9, ContainerLayout.Source.FIXED), info.layout());
        assertEquals(41, info.size());
    }

    @Test void knownWorkstation_isNonHostEvenWithPlayerInventorySlots() {
        var menu = new Menu(MenuType.CRAFTING, 1);
        menu.add(new Slot(inventory, 0, 0, 0));
        player.containerMenu = menu;
        assertEquals(ContainerMenuInfo.NON_HOST, ContainerMenuInfos.resolve(player));
    }

    @Test void unknownMenu_isUnavailableNotUnsupported() {
        var menu = new Menu(MenuType.GENERIC_9x3, 1);
        menu.add(new Slot(inventory, 0, 0, 0));
        player.containerMenu = menu;
        assertEquals(ContainerMenuInfo.UNAVAILABLE, ContainerMenuInfos.resolve(player));
    }

    @Test void knownWorkstations_areExplicitlyClassified() {
        for (var type : List.of(MenuType.CRAFTING, MenuType.ANVIL, MenuType.MERCHANT,
                MenuType.LOOM, MenuType.STONECUTTER, MenuType.CARTOGRAPHY_TABLE, MenuType.GRINDSTONE,
                MenuType.SMITHING, MenuType.ENCHANTMENT, MenuType.BEACON, MenuType.LECTERN)) {
            assertTrue(ContainerMenuInfos.isNonHost(type));
        }
        assertFalse(ContainerMenuInfos.isNonHost(MenuType.GENERIC_9x3));
        assertFalse(ContainerMenuInfos.isNonHost(MenuType.FURNACE));
    }

    @Test void playerSlots_doNotReplaceExternalContainer() {
        var menu = new Menu(MenuType.FURNACE, 1);
        menu.add(new Slot(inventory, 0, 0, 0));
        target(menu);
        assertEquals(ContainerKind.WORLD_BLOCK, ContainerMenuInfos.resolve(player).kind());
    }

    @Test void brokenSource_degradesToUnavailable() {
        var menu = new Menu(MenuType.FURNACE, 1);
        var be = target(menu);
        ItemFormContainers.register(new ItemFormContainers.Source() {
            public boolean isContainerItem(ItemStack s) { return false; }
            public net.neoforged.neoforge.items.IItemHandler contentHandler(ItemStack s) { return null; }
            public int columnsOfBlockEntity(net.minecraft.world.level.block.entity.BlockEntity b) {
                if (b == be) throw new IllegalStateException("test source");
                return 0;
            }
        });
        assertEquals(ContainerMenuInfo.UNAVAILABLE, ContainerMenuInfos.resolve(player));
    }

    @Test void layoutSources_shareActualWidthAndPriority() {
        var menu = new Menu(MenuType.FURNACE, 1);
        var be = target(menu);
        var ctx = new SimpleContainerContext(new ItemStackHandler(3), List.of(BlockPos.ZERO), List.of(be));
        assertEquals(ContainerLayout.Source.FAMILY, ctx.getLayout().source());
        LearnedLayouts.learn(ID, 3, 3);
        assertEquals(ContainerLayout.Source.LEARNED, ctx.getLayout().source());
        assertEquals(3, ctx.getWidth());
        ContainerCompatibilityConfig.register(ID,
            ContainerCompatibilityConfig.ContainerRule.builder().containerSize(3).columns(1).build());
        assertEquals(ContainerLayout.Source.RULE, ctx.getLayout().source());
        ItemFormContainers.register(new ItemFormContainers.Source() {
            public boolean isContainerItem(ItemStack s) { return false; }
            public net.neoforged.neoforge.items.IItemHandler contentHandler(ItemStack s) { return null; }
            public int columnsOfBlockEntity(net.minecraft.world.level.block.entity.BlockEntity b) { return b == be ? 3 : 0; }
        });
        assertEquals(ContainerLayout.Source.LIVE, ctx.getLayout().source());
        assertEquals(ctx.getWidth(), ctx.getLayout().columns());
    }

    @Test void heuristicAndItemFormSources_remainDistinct() {
        var ctx = new SimpleContainerContext(new ItemStackHandler(30));
        assertEquals(new ContainerLayout(10, ContainerLayout.Source.HEURISTIC), ctx.getLayout());
        var itemCtx = new ItemFormContainerContext(new ItemStackHandler(30), java.util.UUID.randomUUID(), null, 5, null);
        assertEquals(new ContainerLayout(5, ContainerLayout.Source.LIVE), itemCtx.getLayout());
        assertEquals(5, itemCtx.getWidth());
    }
}
