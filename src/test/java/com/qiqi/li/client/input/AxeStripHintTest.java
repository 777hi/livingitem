package com.qiqi.li.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.qiqi.li.client.util.ClientMenuSlots;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingItemRegistrationScope;
import com.qiqi.li.living.domain.tools.AxeStripHandler;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class AxeStripHintTest {
    private List<InteractionEntry> entries;
    private List<InteractionEntry> savedEntries;
    private LivingItemRegistrationScope functions;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        entries = registeredEntries();
        savedEntries = List.copyOf(entries);
        entries.clear();
        functions = LivingItemRegistrationScope.isolated(List.of());
        InteractionRegistry.register(new InteractionEntry(Items.OAK_LOG, null, 1,
            AxeStripHandler.ACTION_ID, false, AxeStripHandler::canStripWith));
    }

    @AfterEach
    void tearDown() {
        try {
            if (savedEntries != null) {
                entries.clear();
                entries.addAll(savedEntries);
            }
        } finally {
            if (functions != null) functions.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 512})
    @DisplayName("生存整堆显示去皮产物与原数量，基础损耗恒为 1")
    void survival_wholeStackCostsOne(int count) {
        ItemStack tool = axe(1);
        ItemStack target = living(Items.OAK_LOG, count);
        ItemStack toolBefore = tool.copy();
        ItemStack targetBefore = target.copy();

        List<Component> lines = AxeStripHint.tryBuildLines(tool, target, false);

        assertEquals(2, lines.size());
        assertEquals("hint.livingitem.axe_strip.result", keyOf(lines.get(0)));
        assertEquals(new ItemStack(Items.STRIPPED_OAK_LOG).getHoverName(), argsOf(lines.get(0))[0]);
        assertEquals(count, argsOf(lines.get(0))[1]);
        assertEquals("hint.livingitem.axe_strip.cost", keyOf(lines.get(1)));
        assertEquals(1, argsOf(lines.get(1))[0]);
        assertTrue(ItemStack.matches(toolBefore, tool));
        assertTrue(ItemStack.matches(targetBefore, target));
    }

    @Test
    @DisplayName("创造模式显示完整产物，但不消耗耐久")
    void creative_noDurabilityCost() {
        List<Component> lines = AxeStripHint.tryBuildLines(axe(1), living(Items.OAK_LOG, 64), true);

        assertEquals(2, lines.size());
        assertEquals("hint.livingitem.axe_strip.result", keyOf(lines.get(0)));
        assertEquals(64, argsOf(lines.get(0))[1]);
        assertEquals("hint.livingitem.axe_strip.cost_creative", keyOf(lines.get(1)));
        assertEquals(0, argsOf(lines.get(1)).length);
    }

    @Test
    @DisplayName("多件工具只提示拆分，不修改物品，也不让原工具命中交互")
    void multipleTools_splitHintDoesNotEnableInteraction() {
        ItemStack tool = axe(3);
        ItemStack target = living(Items.OAK_LOG, 16);
        ItemStack toolBefore = tool.copy();
        ItemStack targetBefore = target.copy();

        for (boolean creative : new boolean[] {false, true}) {
            List<Component> lines = AxeStripHint.tryBuildLines(tool, target, creative);
            assertEquals(1, lines.size());
            assertEquals("hint.livingitem.axe_strip.split", keyOf(lines.getFirst()));
            assertNull(InteractionRegistry.findInteraction(tool, target, 1, false));
        }
        assertTrue(ItemStack.matches(toolBefore, tool));
        assertTrue(ItemStack.matches(targetBefore, target));
    }

    @Test
    @DisplayName("空栈、非活物品、无去皮能力与无转换结果不提示")
    void ineligibleStacks_noHint() {
        ItemStack target = living(Items.OAK_LOG, 16);
        assertNull(AxeStripHint.tryBuildLines(ItemStack.EMPTY, target, false));
        assertNull(AxeStripHint.tryBuildLines(axe(1), ItemStack.EMPTY, false));
        assertNull(AxeStripHint.tryBuildLines(new ItemStack(Items.IRON_AXE), target, false));
        assertNull(AxeStripHint.tryBuildLines(axe(1), new ItemStack(Items.OAK_LOG), false));
        assertNull(AxeStripHint.tryBuildLines(living(Items.IRON_HOE, 1), target, false));
        assertNull(AxeStripHint.tryBuildLines(axe(1), living(Items.STRIPPED_OAK_LOG, 1), false));
        assertNull(AxeStripHint.tryBuildLines(axe(1), living(Items.OAK_PLANKS, 1), false));
    }

    @Test
    @DisplayName("能转换但不在规则候选内的木料不显示正常或拆分提示")
    void convertibleButUnmatched_noHint() {
        ItemStack target = living(Items.SPRUCE_LOG, 16);
        assertTrue(AxeStripHandler.canStripWith(axe(1), target));
        assertNoHint(target);
    }

    @Test
    @DisplayName("规则删除后，单件与多件工具均不误报")
    void removedRule_noHint() {
        entries.clear();
        assertNoHint(living(Items.OAK_LOG, 16));
    }

    @Test
    @DisplayName("其它精确动作覆盖去皮通配规则时不误报")
    void overridingAction_noHint() {
        InteractionRegistry.register(new InteractionEntry(Items.OAK_LOG, Items.IRON_AXE, 1, "other_action"));
        ItemStack target = living(Items.OAK_LOG, 16);

        assertEquals("other_action", InteractionRegistry.findInteraction(axe(1), target, 1, false).actionId());
        assertNoHint(target);
    }

    @Test
    @DisplayName("左键或释放时触发的去皮规则不显示右键按下提示")
    void differentClickPhase_noHint() {
        entries.clear();
        InteractionRegistry.register(new InteractionEntry(Items.OAK_LOG, null, 0,
            AxeStripHandler.ACTION_ID, false, AxeStripHandler::canStripWith));
        InteractionRegistry.register(new InteractionEntry(Items.OAK_LOG, null, 1,
            AxeStripHandler.ACTION_ID, true, AxeStripHandler::canStripWith));
        assertNoHint(living(Items.OAK_LOG, 16));
    }

    @Test
    @DisplayName("规则放宽也不能让非活工具或不支持的材料显示可去皮")
    void permissiveRule_stillRequiresStripEligibility() {
        entries.clear();
        InteractionRegistry.register(new InteractionEntry(Items.OAK_LOG, null, 1, AxeStripHandler.ACTION_ID));
        InteractionRegistry.register(new InteractionEntry(Items.OAK_PLANKS, null, 1, AxeStripHandler.ACTION_ID));

        assertNull(AxeStripHint.tryBuildLines(new ItemStack(Items.IRON_AXE), living(Items.OAK_LOG, 1), false));
        assertNull(AxeStripHint.tryBuildLines(living(Items.IRON_HOE, 1), living(Items.OAK_LOG, 1), false));
        assertNoHint(living(Items.OAK_PLANKS, 16));
    }

    @Test
    void craftingAndResultSlotsNeitherHintNorConsumeClick() {
        for (boolean result : new boolean[] {false, true}) {
            HintMenu menu = new HintMenu();
            var container = result ? new ResultContainer() : new TransientCraftingContainer(menu, 2, 2);
            container.setItem(0, living(Items.OAK_LOG, 64));
            Slot slot = new Slot(container, 0, 0, 0);
            menu.attach(slot);
            menu.setCarried(axe(1));
            try (var mapping = mockStatic(ClientMenuSlots.class);
                 var packets = mockStatic(PacketDistributor.class)) {
                mapping.when(() -> ClientMenuSlots.serverMenuSlot(menu, slot)).thenReturn(0);
                mapping.when(() -> ClientMenuSlots.serverContainerSlot(menu, slot)).thenReturn(0);
                mapping.when(() -> ClientMenuSlots.serverMenuId(menu)).thenReturn(0);
                mapping.when(() -> ClientMenuSlots.isStorageSlot(menu, slot)).thenReturn(true);

                assertNull(AxeStripHint.getHintLines(menu, slot));
                assertFalse(GuiInteractionHelper.tryInteract(slot, 1, false, menu));
                assertEquals(64, container.getItem(0).getCount());
                assertTrue(container.getItem(0).is(Items.OAK_LOG));
                assertEquals(0, menu.getCarried().getDamageValue());
                packets.verifyNoInteractions();
            }
        }
    }

    @Test
    void storageGateIsSharedAndDoesNotRejectOrdinaryStorage() {
        HintMenu menu = new HintMenu();
        Slot slot = new Slot(new SimpleContainer(9), 0, 0, 0);
        menu.attach(slot);
        try (var mapping = mockStatic(ClientMenuSlots.class)) {
            mapping.when(() -> ClientMenuSlots.isStorageSlot(menu, slot)).thenReturn(true);
            assertTrue(GuiInteractionHelper.supportsAxeStrip(menu, slot));
            mapping.when(() -> ClientMenuSlots.isStorageSlot(menu, slot)).thenReturn(false);
            assertFalse(GuiInteractionHelper.supportsAxeStrip(menu, slot));
        }
    }

    private static class HintMenu extends AbstractContainerMenu {
        HintMenu() { super(null, 0); }
        void attach(Slot slot) { addSlot(slot); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    }

    private static void assertNoHint(ItemStack target) {
        for (int count : new int[] {1, 3}) {
            assertNull(AxeStripHint.tryBuildLines(axe(count), target, false));
            assertNull(AxeStripHint.tryBuildLines(axe(count), target, true));
        }
    }

    private static ItemStack axe(int count) {
        return living(Items.IRON_AXE, count);
    }

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static String keyOf(Component component) {
        return assertInstanceOf(TranslatableContents.class, component.getContents()).getKey();
    }

    private static Object[] argsOf(Component component) {
        return assertInstanceOf(TranslatableContents.class, component.getContents()).getArgs();
    }

    @SuppressWarnings("unchecked")
    private static List<InteractionEntry> registeredEntries() throws ReflectiveOperationException {
        Field field = InteractionRegistry.class.getDeclaredField("ENTRIES");
        field.setAccessible(true);
        return (List<InteractionEntry>) field.get(null);
    }
}
