package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingItemRegistrationScope;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.domain.tools.AxeStripHandler;
import com.qiqi.li.living.interaction.InteractionAction;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.MenuActionAccess;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.network.PacketDistributor;

/** 精确菜单槽身份及去皮请求的分发、访问、真实库存写回接缝。 */
@Isolated
class GuiInteractionPacketTest {

    @Test
    @DisplayName("空槽不是物品状态动作目标；容器动作须另行声明范围")
    void emptySlot_isInvalidItemTarget() {
        assertFalse(GuiInteractionPacket.isValidTarget(ItemStack.EMPTY));
    }

    @Test
    @DisplayName("非空活物品可作为物品状态动作目标")
    void livingItem_isValid() {
        ItemStack living = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(living, true);
        assertTrue(GuiInteractionPacket.isValidTarget(living));
    }

    @Test
    @DisplayName("非活物品不能作为物品状态动作目标")
    void nonLivingItem_isInvalid() {
        assertFalse(GuiInteractionPacket.isValidTarget(new ItemStack(Items.STONE)));
    }

    @Test
    @DisplayName("双索引不符时拒绝；所有动作均不回退到另一物理同号槽")
    void mismatchedPhysicalIndex_neverFallsBack() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));
        menu.attach(slot(13));

        assertNull(GuiInteractionPacket.resolveSlot(menu, 0, 13));
        assertSame(menu.getSlot(1), GuiInteractionPacket.resolveSlot(menu, 1, 13));
    }

    @Test
    @DisplayName("同物理编号的不同库存按精确菜单索引选择，不比较货物内容")
    void duplicatePhysicalIndex_selectsExactMenuEntry() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));
        menu.attach(slot(0));

        assertSame(menu.getSlot(0), GuiInteractionPacket.resolveSlot(menu, 0, 0));
        assertSame(menu.getSlot(1), GuiInteractionPacket.resolveSlot(menu, 1, 0));
    }

    @Test
    @DisplayName("越界菜单索引与负物理编号拒绝，不能按物理编号找回目标")
    void invalidIndices_areRejected() {
        FakeMenu menu = new FakeMenu();
        menu.attach(slot(0));

        assertNull(GuiInteractionPacket.resolveSlot(menu, -1, 0));
        assertNull(GuiInteractionPacket.resolveSlot(menu, 1, 0));
        assertNull(GuiInteractionPacket.resolveSlot(menu, Integer.MAX_VALUE, 0));
        assertNull(GuiInteractionPacket.resolveSlot(menu, 0, -1));
    }

    @Test
    @DisplayName("非 active 槽即使双索引匹配也拒绝")
    void inactiveSlot_isRejected() {
        FakeMenu menu = new FakeMenu();
        menu.attach(new Slot(new SimpleContainer(9), 0, 0, 0) {
            @Override public boolean isActive() { return false; }
        });
        menu.attach(slot(0));

        assertNull(GuiInteractionPacket.resolveSlot(menu, 0, 0));
        assertSame(menu.getSlot(1), GuiInteractionPacket.resolveSlot(menu, 1, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("去皮请求穿过真实授权与玩家能力解析：同号同内容库存只改精确点击格")
    void axeStrip_dispatchTransformsOnlyClickedInventory(int selected) throws Exception {
        try (AxeFixture f = new AxeFixture()) {
            Slot target = new Slot(f.inventory, 0, 0, 0);
            Slot decoy = new Slot(f.other, 0, 0, 0);
            FakeMenu menu = f.menu(selected == 0 ? target : decoy, selected == 0 ? decoy : target);
            ItemStack originalTool = menu.getCarried();
            ItemStack expected = living(Items.STRIPPED_OAK_LOG, 64);

            GuiInteractionPacket.handle(f.packet(selected, 0, menu.containerId), f.player);

            assertTrue(ItemStack.matches(expected, f.inventory.getItem(0)));
            assertTrue(ItemStack.matches(f.source, f.other.getItem(0)));
            assertTrue(ItemStack.matches(f.source, f.inventory.getItem(1)));
            assertEquals(1, menu.getCarried().getDamageValue(), "整堆只损耗一次");
            assertEquals(0, originalTool.getDamageValue(), "光标副本结算不得修改旧引用");
            assertTrue(LivingItemManager.isLivingItem(menu.getCarried()));
            verify(f.handler).handle(f.player, target);
            verify(f.capability).setStackInSlot(eq(0), argThat(stack -> ItemStack.matches(expected, stack)));
            f.assertNoExtractInsert();
            verify(f.player).playNotifySound(SoundEvents.AXE_STRIP, SoundSource.PLAYERS, 1.0F, 1.0F);
            assertEquals(1, menu.broadcasts);
            f.packets.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_menu", "wrong_physical", "invalid_menu", "inventory_out_of_reach", "unknown_slot", "explicit_deny"})
    @DisplayName("去皮请求身份或真实访问门拒绝：不进入handler、不写库存、不扣光标")
    void axeStrip_rejectedRequestDoesNotWriteOrCharge(String rejection) throws Exception {
        try (AxeFixture f = new AxeFixture()) {
            Slot target = rejection.equals("unknown_slot") ? new Slot(f.inventory, 0, 0, 0) {
                @Override public boolean mayPickup(Player player) { return true; }
            } : new Slot(f.inventory, 0, 0, 0);
            FakeMenu menu = f.menu(target, new Slot(f.other, 0, 0, 0), new Slot(f.inventory, 1, 0, 0));
            ItemStack originalTool = menu.getCarried();
            int menuId = menu.containerId;
            int physical = 0;
            switch (rejection) {
                case "wrong_menu" -> menuId++;
                case "wrong_physical" -> physical = 1;
                case "invalid_menu" -> menu.valid = false;
                case "inventory_out_of_reach" -> when(f.player.canInteractWithEntity(f.player, 4.0)).thenReturn(false);
                case "unknown_slot" -> assertTrue(target.mayPickup(f.player), "可拿取不能替代状态授权");
                case "explicit_deny" -> MenuActionAccess.register(new MenuActionAccess.Resolver() {
                    @Override public boolean supports(AbstractContainerMenu candidate) { return candidate == menu; }
                    @Override public MenuActionAccess.Decision check(MenuActionAccess.Request request) {
                        assertSame(target, request.target());
                        assertEquals(InteractionAction.Kind.ITEM_STATE, request.kind());
                        return MenuActionAccess.Decision.DENY;
                    }
                });
                default -> throw new AssertionError(rejection);
            }

            GuiInteractionPacket.handle(f.packet(0, physical, menuId), f.player);

            f.assertUnchanged(menu, originalTool);
            verifyNoInteractions(f.handler, f.capability, f.player.connection);
            assertEquals(0, menu.broadcasts);
            f.packets.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("真实解析出的能力拒绝精确去皮写回：不降级抽插、不损耗")
    void axeStrip_exactWriteRejectionDoesNotFallBackToExtractInsert() throws Exception {
        try (AxeFixture f = new AxeFixture()) {
            f.capability = spy(new InvWrapper(f.inventory) {
                @Override public void setStackInSlot(int slot, ItemStack stack) {
                    if (stack.is(Items.STRIPPED_OAK_LOG)) throw new UnsupportedOperationException("read only");
                    super.setStackInSlot(slot, stack);
                }
            });
            when(f.player.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(f.capability);
            FakeMenu menu = f.menu(new Slot(f.inventory, 0, 0, 0));
            ItemStack originalTool = menu.getCarried();

            GuiInteractionPacket.handle(f.packet(0, 0, menu.containerId), f.player);

            verify(f.handler).handle(f.player, menu.getSlot(0));
            verify(f.capability).extractItem(0, 64, true);
            verify(f.capability).setStackInSlot(eq(0), argThat(stack -> stack.is(Items.STRIPPED_OAK_LOG)));
            f.assertNoExtractInsert();
            f.assertUnchanged(menu, originalTool);
            verifyNoInteractions(f.player.connection);
            f.packets.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("创造虚拟活斧穿过真实去皮链：产物落在库存、回传实际光标并恢复原光标")
    void axeStrip_creativeVirtualCursorTransformsAndReplies() throws Exception {
        try (AxeFixture f = new AxeFixture()) {
            InventoryMenu menu = f.player.inventoryMenu;
            f.player.containerMenu = menu;
            when(f.player.isCreative()).thenReturn(true);
            when(f.player.hasInfiniteMaterials()).thenReturn(true);
            ItemStack original = new ItemStack(Items.DIAMOND, 3);
            menu.setCarried(original);
            ItemStack virtual = living(Items.IRON_AXE, 1);
            virtual.setDamageValue(7);
            virtual.set(DataComponents.CUSTOM_NAME, Component.literal("virtual axe"));
            CompoundTag carried = (CompoundTag) virtual.saveOptional(f.registry);
            CompoundTag sent = carried.copy();

            GuiInteractionPacket.handle(new GuiInteractionPacket(36, 0, AxeStripHandler.ACTION_ID,
                carried, menu.containerId, 73), f.player);

            assertTrue(ItemStack.matches(living(Items.STRIPPED_OAK_LOG, 64), f.inventory.getItem(0)));
            assertTrue(ItemStack.matches(f.source, f.inventory.getItem(1)));
            assertTrue(ItemStack.matches(f.source, f.other.getItem(0)));
            assertSame(original, menu.getCarried());
            assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND, 3), original));
            assertEquals(sent, carried, "请求中的虚拟光标数据不能被就地修改");
            verify(f.handler).handle(f.player, menu.getSlot(36));
            verify(f.player).playNotifySound(SoundEvents.AXE_STRIP, SoundSource.PLAYERS, 1.0F, 1.0F);
            f.assertNoExtractInsert();
            ArgumentCaptor<CarriedUpdatePacket> reply = ArgumentCaptor.forClass(CarriedUpdatePacket.class);
            f.packets.verify(() -> PacketDistributor.sendToPlayer(eq(f.player), reply.capture()));
            f.packets.verifyNoMoreInteractions();
            assertEquals(menu.containerId, reply.getValue().menuId());
            assertEquals(73, reply.getValue().requestId());
            ItemStack actualCursor = ItemStack.parse(f.registry, reply.getValue().carriedTag()).orElseThrow();
            assertTrue(ItemStack.matches(virtual, actualCursor), "创造回传的是借入的活斧，而非原光标或木料");
        }
    }

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** resolve / 权限 / 原语均为真实路径；只有玩家、世界及两种发包边界使用 mock。 */
    private static final class AxeFixture implements AutoCloseable {
        final ServerPlayer player = mock(ServerPlayer.class);
        final Inventory inventory = new Inventory(player);
        final SimpleContainer other = new SimpleContainer(9);
        final ItemStack source = living(Items.OAK_LOG, 64);
        final RegistryAccess registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final AxeStripHandler handler = spy(new AxeStripHandler());
        final String playerId = UUID.randomUUID().toString();
        final List<InteractionEntry> entries;
        final Map<String, InteractionAction> actions;
        final List<MenuActionAccess.Resolver> resolvers;
        final List<InteractionEntry> savedEntries;
        final Map<String, InteractionAction> savedActions;
        final List<MenuActionAccess.Resolver> savedResolvers;
        final LivingItemRegistrationScope functions;
        final MockedStatic<PacketDistributor> packets;
        InvWrapper capability = spy(new InvWrapper(inventory));

        @SuppressWarnings("unchecked")
        AxeFixture() throws Exception {
            entries = (List<InteractionEntry>) staticField(InteractionRegistry.class, "ENTRIES");
            actions = (Map<String, InteractionAction>) staticField(InteractionRegistry.class, "HANDLERS");
            resolvers = (List<MenuActionAccess.Resolver>) staticField(MenuActionAccess.class, "RESOLVERS");
            savedEntries = new ArrayList<>(entries);
            savedActions = new HashMap<>(actions);
            savedResolvers = new ArrayList<>(resolvers);
            when(player.level()).thenReturn(mock(ServerLevel.class));
            when(player.registryAccess()).thenReturn(registry);
            when(player.getStringUUID()).thenReturn(playerId);
            when(player.getInventory()).thenReturn(inventory);
            when(player.canInteractWithEntity(player, 4.0)).thenReturn(true);
            when(player.getCapability(Capabilities.ItemHandler.ENTITY)).thenReturn(capability);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            InventoryMenu inventoryMenu = new InventoryMenu(inventory, false, player);
            Field field = Player.class.getDeclaredField("inventoryMenu");
            field.setAccessible(true);
            field.set(player, inventoryMenu);
            inventory.setItem(0, source.copy());
            inventory.setItem(1, source.copy());
            other.setItem(0, source.copy());
            packets = mockStatic(PacketDistributor.class);
            functions = LivingItemRegistrationScope.isolated(List.of());
            entries.clear();
            actions.clear();
            resolvers.clear();
            // 单测不加载物品 tags；用具体物品保留真实规则匹配及组合过滤。
            InteractionRegistry.register(new InteractionEntry(Items.OAK_LOG, null, 1,
                AxeStripHandler.ACTION_ID, false, AxeStripHandler::canStripWith));
            InteractionRegistry.registerHandler(AxeStripHandler.ACTION_ID, handler);
        }

        FakeMenu menu(Slot... slots) {
            FakeMenu menu = new FakeMenu(17);
            for (Slot slot : slots) menu.attach(slot);
            menu.setCarried(living(Items.IRON_AXE, 1));
            player.containerMenu = menu;
            return menu;
        }

        GuiInteractionPacket packet(int menuSlot, int physical, int menuId) {
            return new GuiInteractionPacket(menuSlot, physical, AxeStripHandler.ACTION_ID, null, menuId);
        }

        void assertNoExtractInsert() {
            verify(capability, never()).extractItem(anyInt(), anyInt(), eq(false));
            verify(capability, never()).insertItem(anyInt(), any(), anyBoolean());
        }

        void assertUnchanged(AbstractContainerMenu menu, ItemStack originalTool) {
            assertTrue(ItemStack.matches(source, inventory.getItem(0)));
            assertTrue(ItemStack.matches(source, inventory.getItem(1)));
            assertTrue(ItemStack.matches(source, other.getItem(0)));
            assertSame(originalTool, menu.getCarried());
            assertTrue(ItemStack.matches(living(Items.IRON_AXE, 1), originalTool));
            verify(player, never()).playNotifySound(any(), any(), anyFloat(), anyFloat());
        }

        @Override public void close() throws Exception {
            try {
                // 玩家键为夹具独占；真实同步与门面查询会创建修订/回收条目。
                for (String name : List.of("CONTAINER_DATA", "LAST_SEEN")) {
                    ((Map<?, ?>) staticField(ContainerLivingItemHandler.class, name)).remove("player_" + playerId);
                }
            } finally {
                entries.clear();
                entries.addAll(savedEntries);
                actions.clear();
                actions.putAll(savedActions);
                resolvers.clear();
                resolvers.addAll(savedResolvers);
                functions.close();
                packets.close();
            }
        }
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Slot slot(int physical) {
        return new Slot(new SimpleContainer(41), physical, 0, 0);
    }

    private static class FakeMenu extends AbstractContainerMenu {
        boolean valid = true;
        int broadcasts;
        FakeMenu() { this(0); }
        FakeMenu(int id) { super(null, id); }
        void attach(Slot slot) { addSlot(slot); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return valid; }
        @Override public void broadcastChanges() { broadcasts++; super.broadcastChanges(); }
    }
}
