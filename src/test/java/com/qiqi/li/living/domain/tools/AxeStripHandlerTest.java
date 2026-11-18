package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingItemRegistrationScope;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class AxeStripHandlerTest {
    private LivingItemRegistrationScope functions;

    @BeforeEach
    void isolateFunctions() {
        functions = LivingItemRegistrationScope.isolated(List.of());
    }

    @AfterEach
    void restoreFunctions() {
        functions.close();
    }

    @Test
    void officialConversionsKeepCountAndLivingMarker() {
        Map<Item, Item> pairs = Map.of(
            Items.OAK_LOG, Items.STRIPPED_OAK_LOG,
            Items.OAK_WOOD, Items.STRIPPED_OAK_WOOD,
            Items.CRIMSON_STEM, Items.STRIPPED_CRIMSON_STEM,
            Items.CRIMSON_HYPHAE, Items.STRIPPED_CRIMSON_HYPHAE,
            Items.WARPED_STEM, Items.STRIPPED_WARPED_STEM,
            Items.WARPED_HYPHAE, Items.STRIPPED_WARPED_HYPHAE,
            Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK);
        pairs.forEach((input, output) -> {
            ItemStack source = living(input, 512);
            ItemStack result = AxeStripHandler.strippedResult(source);
            assertTrue(result.is(output));
            assertEquals(512, result.getCount());
            assertTrue(LivingItemManager.isLivingItem(result));
            assertFalse(result.has(DataComponents.BLOCK_STATE));
            assertTrue(source.is(input));
        });
    }

    @Test
    void componentPatchAndRecognizedBlockStateArePreserved() {
        ItemStack source = living(Items.OAK_LOG, 64);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("named wood"));
        source.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("lore"))));
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", "test");
        source.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        source.remove(DataComponents.RARITY);
        source.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of("axis", "x", "unknown", "v")));

        ItemStack result = AxeStripHandler.strippedResult(source);

        assertEquals(source.get(DataComponents.CUSTOM_NAME), result.get(DataComponents.CUSTOM_NAME));
        assertEquals(source.get(DataComponents.LORE), result.get(DataComponents.LORE));
        assertEquals(source.get(DataComponents.CUSTOM_DATA), result.get(DataComponents.CUSTOM_DATA));
        assertFalse(result.has(DataComponents.RARITY), "显式删除补丁不得丢失");
        assertEquals(Map.of("axis", "x"), result.get(DataComponents.BLOCK_STATE).properties());
        assertEquals(Direction.Axis.X, result.get(DataComponents.BLOCK_STATE).get(BlockStateProperties.AXIS));
    }

    @Test
    void unsupportedOrAlreadyStrippedMaterialIsRejected() {
        for (Item item : List.of(Items.OAK_PLANKS, Items.STRIPPED_OAK_LOG, Items.STONE, Items.STICK)) {
            assertTrue(AxeStripHandler.strippedResult(living(item, 1)).isEmpty());
        }
        assertTrue(AxeStripHandler.strippedResult(new ItemStack(Items.OAK_LOG)).isEmpty());
    }

    @Test
    void containerPayloadsAndIdentitiesAreRejected() {
        ItemStack source = living(Items.OAK_LOG, 1);
        source.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        assertTrue(AxeStripHandler.strippedResult(source).isEmpty());
        source.remove(DataComponents.CONTAINER);
        source.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY);
        assertTrue(AxeStripHandler.strippedResult(source).isEmpty());
        source.remove(DataComponents.BLOCK_ENTITY_DATA);
        source.set(LivingComponents.CONTAINER_UUID.get(), UUID.randomUUID());
        assertTrue(AxeStripHandler.strippedResult(source).isEmpty());
    }

    @Test
    void claimedSourceOrResultIsRejected() {
        for (Item claimed : List.of(Items.OAK_LOG, Items.STRIPPED_OAK_LOG)) {
            LivingItemFunction function = mock(LivingItemFunction.class);
            when(function.getFunctionId()).thenReturn("claimed_wood");
            when(function.canApply(any())).thenAnswer(call -> ((ItemStack) call.getArgument(0)).is(claimed));
            try (var scope = LivingItemRegistrationScope.withAdditional(function)) {
                assertTrue(AxeStripHandler.strippedResult(living(Items.OAK_LOG, 1)).isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 64, 512, 2147483647})
    void wholeStackUsesExactlyOneDurabilityAndKeepsMemory(int count) {
        Fixture f = new Fixture(count);
        LivingToolMemory memory = new LivingToolMemory(null,
            new LivingToolMemory.RayMemory(new Vec3(1, 2, 3), Blocks.OAK_LOG), null);
        f.menu.getCarried().set(ToolComponents.LIVING_TOOL_MEMORY.get(), memory);
        ItemStack originalTool = f.menu.getCarried();
        f.invoke();

        assertTrue(f.storage.getStackInSlot(0).is(Items.STRIPPED_OAK_LOG));
        assertEquals(count, f.storage.getStackInSlot(0).getCount());
        assertEquals(1, f.menu.getCarried().getDamageValue());
        assertEquals(0, originalTool.getDamageValue(), "光标必须副本损耗后提交");
        assertEquals(memory, f.menu.getCarried().get(ToolComponents.LIVING_TOOL_MEMORY.get()));
        verify(f.player).playNotifySound(SoundEvents.AXE_STRIP, SoundSource.PLAYERS, 1.0F, 1.0F);
        verify(f.context).syncSlotToClients(eq(0), any());
    }

    @Test
    void brokenToolBecomesEmptyButMaterialTransformationRemains() {
        Fixture f = new Fixture(64);
        f.menu.getCarried().setDamageValue(f.menu.getCarried().getMaxDamage() - 1);
        f.invoke();
        assertTrue(f.menu.getCarried().isEmpty());
        assertTrue(f.storage.getStackInSlot(0).is(Items.STRIPPED_OAK_LOG));
        verify(f.player).onEquippedItemBroken(Items.IRON_AXE, EquipmentSlot.MAINHAND);
    }

    @Test
    void creativeAndUnbreakableToolsStillTransformWithoutDamage() {
        for (boolean creative : new boolean[] {true, false}) {
            Fixture f = new Fixture(64);
            when(f.player.hasInfiniteMaterials()).thenReturn(creative);
            if (!creative) f.menu.getCarried().set(DataComponents.UNBREAKABLE, new Unbreakable(true));
            f.invoke();
            assertEquals(0, f.menu.getCarried().getDamageValue());
            assertEquals(64, f.storage.getStackInSlot(0).getCount());
            assertTrue(f.storage.getStackInSlot(0).is(Items.STRIPPED_OAK_LOG));
        }
    }

    @Test
    void rejectedWritesDoNotChargeOrPlaySound() {
        for (String mode : List.of("noop", "truncate", "throw")) {
            ItemStackHandler storage = new LargeStackStorage() {
                @Override
                public void setStackInSlot(int slot, ItemStack stack) {
                    if (stack.is(Items.STRIPPED_OAK_LOG)) {
                        switch (mode) {
                            case "noop" -> { return; }
                            case "throw" -> throw new UnsupportedOperationException("read only");
                            case "truncate" -> stack = stack.copyWithCount(1);
                        }
                    }
                    super.setStackInSlot(slot, stack);
                }
            };
            Fixture f = new Fixture(storage, 64);
            f.invoke();
            assertTrue(storage.getStackInSlot(0).is(Items.OAK_LOG));
            assertEquals(64, storage.getStackInSlot(0).getCount());
            assertTrue(storage.getStackInSlot(1).isEmpty());
            f.assertNoCharge();
        }
    }

    @Test
    void invalidMenuSlotPermissionsAndBackendAdmissionDoNotWrite() {
        for (int condition = 0; condition < 7; condition++) {
            Fixture f = new Fixture(64);
            switch (condition) {
                case 0 -> f.menu.valid = false;
                case 1 -> f.menu.active = false;
                case 2 -> f.menu.pickup = false;
                case 3 -> f.menu.place = false;
                case 4 -> doReturn(false).when(f.context).supportsExactWrite(0);
                case 5 -> doReturn(0).when(f.context).simulateExtractItem(0, 64);
                case 6 -> doReturn(false).when(f.context).isItemValid(eq(0), any());
            }
            f.invoke();
            assertTrue(f.storage.getStackInSlot(0).is(Items.OAK_LOG), "condition=" + condition);
            f.assertNoCharge();
        }
    }

    @Test
    void changedInputCannotBeOverwrittenOrCharged() {
        Fixture f = new Fixture(64);
        doAnswer(call -> {
            f.storage.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
            return call.callRealMethod();
        }).when(f.context).transform(eq(0), any());
        f.invoke();
        assertTrue(f.storage.getStackInSlot(0).is(Items.DIAMOND));
        assertEquals(2, f.storage.getStackInSlot(0).getCount());
        f.assertNoCharge();
    }

    @Test
    void alreadyChangedToExpectedResultStillDoesNotCharge() {
        Fixture f = new Fixture(64);
        doAnswer(call -> {
            f.storage.setStackInSlot(0, living(Items.STRIPPED_OAK_LOG, 64));
            return call.callRealMethod();
        }).when(f.context).transform(eq(0), any());
        f.invoke();
        assertTrue(f.storage.getStackInSlot(0).is(Items.STRIPPED_OAK_LOG));
        f.assertNoCharge();
    }

    @Test
    void unmappedOrDifferentBackendDoesNotWrite() {
        Fixture f = new Fixture(64);
        doReturn(new ItemStack(Items.DIAMOND)).when(f.context).getItem(0);
        f.invoke();
        assertTrue(f.storage.getStackInSlot(0).is(Items.OAK_LOG));
        f.assertNoCharge();
    }

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static class Fixture {
        final ItemStackHandler storage;
        final TestMenu menu;
        final ServerPlayer player = mock(ServerPlayer.class);
        final SimpleContainerContext context;

        Fixture(int count) { this(new LargeStackStorage(), count); }

        Fixture(ItemStackHandler storage, int count) {
            this.storage = storage;
            storage.setStackInSlot(0, living(Items.OAK_LOG, count));
            menu = new TestMenu(storage);
            menu.setCarried(living(Items.IRON_AXE, 1));
            player.containerMenu = menu;
            when(player.level()).thenReturn(mock(ServerLevel.class));
            context = spy(new SimpleContainerContext(storage));
            doNothing().when(context).syncSlotToClients(anyInt(), any());
        }

        void invoke() {
            try (MockedStatic<ContainerContexts> contexts = mockStatic(ContainerContexts.class);
                 MockedStatic<InteractionRegistry> rules = mockStatic(InteractionRegistry.class)) {
                contexts.when(() -> ContainerContexts.resolve(player, menu.getSlot(0))).thenReturn(context);
                rules.when(() -> InteractionRegistry.findInteraction(any(), any(), eq(1), eq(false)))
                    .thenReturn(new InteractionEntry(Items.OAK_LOG, null, 1, AxeStripHandler.ACTION_ID,
                        false, AxeStripHandler::canStripWith));
                new AxeStripHandler().handle(player, menu.getSlot(0));
            }
        }

        void assertNoCharge() {
            assertEquals(0, menu.getCarried().getDamageValue());
            verify(player, never()).playNotifySound(any(), any(), anyFloat(), anyFloat());
        }
    }

    private static class LargeStackStorage extends ItemStackHandler {
        LargeStackStorage() { super(2); }

        @Override public int getSlotLimit(int slot) { return Integer.MAX_VALUE; }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!simulate) return super.extractItem(slot, amount, false);
            ItemStack stack = getStackInSlot(slot);
            return stack.copyWithCount(Math.min(amount, stack.getCount()));
        }
    }

    private static class TestMenu extends AbstractContainerMenu {
        boolean valid = true;
        boolean active = true;
        boolean pickup = true;
        boolean place = true;

        TestMenu(ItemStackHandler handler) {
            super(MenuType.GENERIC_9x1, 1);
            addSlot(new SlotItemHandler(handler, 0, 0, 0) {
                @Override public boolean isActive() { return active; }
                @Override public boolean mayPickup(Player player) { return pickup; }
                @Override public boolean mayPlace(ItemStack stack) { return place; }
            });
        }

        @Override public boolean stillValid(Player player) { return valid; }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    }
}
