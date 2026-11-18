package com.qiqi.li.living.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.tools.AxeStripHandler;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * 活斧去皮的规则匹配边界：能力工具可命中，不合条件的点击必须交回原版。
 * FML 单测不加载 tags，匹配测试使用具体物品规则；内置 tag 声明由配置测试覆盖。
 */
class AxeStripInteractionTest {

    private static final List<Item> STRIPPABLE_TARGETS = List.of(
        Items.OAK_LOG, Items.OAK_WOOD, Items.CRIMSON_STEM, Items.CRIMSON_HYPHAE,
        Items.WARPED_STEM, Items.WARPED_HYPHAE, Items.BAMBOO_BLOCK);

    @BeforeEach
    void setUp() {
        InteractionRegistry.clearForTest();
        InteractionPredicates.clearForTest();
        InteractionRegistry.registerHandler(AxeStripHandler.ACTION_ID, new AxeStripHandler());
        InteractionPredicates.register("can_strip_with", AxeStripHandler::canStripWith);
        for (Item target : STRIPPABLE_TARGETS) {
            registerTarget(target);
        }
        // logs tag 包含已去皮原木，必须由谓词拒绝，而非依赖目标不匹配。
        registerTarget(Items.STRIPPED_OAK_LOG);
    }

    @AfterEach
    void tearDown() {
        InteractionRegistry.clearForTest();
        InteractionPredicates.clearForTest();
    }

    private static void registerTarget(Item target) {
        InteractionRegistry.register(new InteractionEntry(target, null, 1, AxeStripHandler.ACTION_ID,
            false, InteractionPredicates.get("can_strip_with")));
    }

    /** 注册表已冻结；spy 已注册木棍，只声明所需工具能力，不构造新 Item。 */
    private static Item capabilityTool(ItemAbility ability) {
        Item tool = Mockito.spy(Items.STICK);
        Mockito.doReturn(true).when(tool)
            .canPerformAction(Mockito.any(ItemStack.class), Mockito.eq(ability));
        return tool;
    }

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static InteractionEntry find(ItemStack trigger, ItemStack target) {
        return InteractionRegistry.findInteraction(trigger, target, 1, false);
    }

    private static void assertMatches(ItemStack trigger, ItemStack target) {
        InteractionEntry entry = find(trigger, target);
        assertNotNull(entry, "活工具应命中可去皮目标：" + target);
        assertEquals(AxeStripHandler.ACTION_ID, entry.actionId());
    }

    @Test
    @DisplayName("活原版斧右键可去皮原木、木头、菌柄、菌核与整堆竹块")
    void livingVanillaAxesMatchStrippableStacks() {
        for (Item axe : List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
                Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE)) {
            for (Item target : STRIPPABLE_TARGETS) {
                assertMatches(living(axe, 1), living(target, 64));
            }
        }
    }

    @Test
    @DisplayName("非 AxeItem 的能力工具只声明 AXE_STRIP，活化后也命中")
    void capabilityToolMatchesWithoutExtendingAxeItem() {
        Item tool = capabilityTool(ItemAbilities.AXE_STRIP);
        assertFalse(tool instanceof AxeItem);
        ItemStack carried = living(tool, 1);
        assertTrue(carried.canPerformAction(ItemAbilities.AXE_STRIP));
        assertFalse(carried.canPerformAction(ItemAbilities.AXE_DIG), "去皮不能额外要求挖掘能力");
        for (Item target : STRIPPABLE_TARGETS) {
            assertMatches(carried, living(target, 64));
        }
    }

    @Test
    @DisplayName("普通斧与未活化能力工具不拦截，不吞原版拿起和分堆")
    void nonLivingToolsPassThrough() {
        for (Item tool : List.of(Items.IRON_AXE, capabilityTool(ItemAbilities.AXE_STRIP))) {
            assertNull(find(new ItemStack(tool), living(Items.OAK_LOG, 64)));
            assertNull(find(new ItemStack(tool), living(Items.BAMBOO_BLOCK, 64)));
        }
    }

    @Test
    @DisplayName("活斧指向普通木料或竹块不拦截")
    void nonLivingTargetsPassThrough() {
        ItemStack axe = living(Items.IRON_AXE, 1);
        for (Item target : STRIPPABLE_TARGETS) {
            assertNull(find(axe, new ItemStack(target, 64)), "普通木料不能触发去皮：" + target);
        }
    }

    @Test
    @DisplayName("光标工具多于一件不拦截，即使目标可去皮")
    void stackedToolsPassThrough() {
        for (Item tool : List.of(Items.IRON_AXE, capabilityTool(ItemAbilities.AXE_STRIP))) {
            for (int count : List.of(2, 64)) {
                assertNull(find(living(tool, count), living(Items.OAK_LOG, 64)));
                assertNull(find(living(tool, count), living(Items.BAMBOO_BLOCK, 64)));
            }
        }
    }

    @Test
    @DisplayName("同一有效组合只拦截右键按下，左键与松键均放行")
    void leftClickAndReleasePassThrough() {
        ItemStack axe = living(Items.IRON_AXE, 1);
        ItemStack target = living(Items.OAK_LOG, 64);
        assertMatches(axe, target);
        assertNull(InteractionRegistry.findInteraction(axe, target, 0, false));
        assertNull(InteractionRegistry.findInteraction(axe, target, 0, true));
        assertNull(InteractionRegistry.findInteraction(axe, target, 1, true));
    }

    @Test
    @DisplayName("已去皮原木即使命中目标规则，谓词也必须放行原版")
    void alreadyStrippedTargetPassesThrough() {
        ItemStack axe = living(Items.IRON_AXE, 1);
        ItemStack target = living(Items.STRIPPED_OAK_LOG, 64);
        assertTrue(InteractionRegistry.getEntries().stream().anyMatch(e -> e.matchesTarget(target)));
        assertFalse(AxeStripHandler.canStripWith(axe, target));
        assertNull(find(axe, target));
    }

    @Test
    @DisplayName("木板不能去皮，不拦截原版槽位操作")
    void planksPassThrough() {
        ItemStack axe = living(Items.IRON_AXE, 1);
        ItemStack target = living(Items.OAK_PLANKS, 64);
        assertFalse(AxeStripHandler.canStripWith(axe, target));
        assertNull(find(axe, target));
    }

    @Test
    @DisplayName("仅 AXE_DIG 的活能力工具不是去皮工具，必须放行")
    void axeDigOnlyToolPassesThrough() {
        ItemStack tool = living(capabilityTool(ItemAbilities.AXE_DIG), 1);
        assertTrue(tool.canPerformAction(ItemAbilities.AXE_DIG));
        assertFalse(tool.canPerformAction(ItemAbilities.AXE_STRIP));
        assertNull(find(tool, living(Items.OAK_LOG, 64)));
        assertNull(find(tool, living(Items.BAMBOO_BLOCK, 64)));
    }

    @Test
    @DisplayName("空光标、无去皮能力的活物品与空目标均不拦截")
    void emptyOrUnrelatedItemsPassThrough() {
        ItemStack target = living(Items.OAK_LOG, 64);
        assertNull(find(ItemStack.EMPTY, target));
        assertNull(find(living(Items.STICK, 1), target));
        assertNull(find(living(Items.DIAMOND_SWORD, 1), target));
        assertNull(find(living(Items.IRON_AXE, 1), ItemStack.EMPTY));
    }
}
