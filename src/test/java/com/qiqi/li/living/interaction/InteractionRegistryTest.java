package com.qiqi.li.living.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 交互注册表匹配测试 —— 两趟优先级（精确触发器 > 通配自交互）回归守卫。
 *
 * <p>回归背景（2026-09-13 游戏实测踩坑）：活耕地的 plant_crop（通配，
 * trigger=null 任意光标）与 bonemeal（精确，trigger=BONE_MEAL）共用
 * target=FARMLAND + button=1。旧实现按注册顺序返回首个匹配 → 骨粉右键
 * 永远命中先注册的 plant_crop，PlantCropHandler 对骨粉静默返回，
 * BonemealHandler 成为死代码（「活骨粉不能催熟」）。修复后 findInteraction
 * 两趟匹配：第一趟只找精确条目，第二趟才找通配条目。</p>
 *
 * <p>测试通过 {@code InteractionRegistry.clearForTest()} 在类级清理静态注册表——
 * ENTRIES/HANDLERS 是全局静态，生产注册发生在 commonSetup，测试环境为空表，
 * 直接注册受测条目。</p>
 */
class InteractionRegistryTest {

    /** 精确条目先于通配注册——故意复现事故现场的最坏注册顺序 */
    private static final InteractionEntry WILDCARD_FARM =
        new InteractionEntry(Items.FARMLAND, null, 1, "plant_crop");
    private static final InteractionEntry PRECISE_BONEMEAL =
        new InteractionEntry(Items.FARMLAND, Items.BONE_MEAL, 1, "bonemeal");

    /** ENTRIES 是类级静态且跨测试累积——每测清空保证各用例独立 */
    @BeforeEach
    void setUp() {
        InteractionRegistry.clearForTest();
    }

    @AfterAll
    static void tearDown() {
        InteractionRegistry.clearForTest();
    }

    private static InteractionEntry find(ItemStack trigger) {
        ItemStack target = new ItemStack(Items.FARMLAND);
        LivingItemManager.setLiving(target, true);
        return InteractionRegistry.findInteraction(trigger, target, 1, false);
    }

    @Test
    @DisplayName("精确触发器不被通配条目遮蔽（骨粉→bonemeal 而非 plant_crop）")
    void preciseEntryBeatsWildcardEvenIfRegisteredLater() {
        // 故意按「通配先注册」的最坏顺序注册——事故现场即此顺序
        InteractionRegistry.register(WILDCARD_FARM);
        InteractionRegistry.register(PRECISE_BONEMEAL);

        ItemStack boneMeal = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(boneMeal, true);

        InteractionEntry matched = find(boneMeal);
        assertEquals("bonemeal", matched.actionId(),
            "精确条目（BONE_MEAL）必须优先于先注册的通配条目");
    }

    @Test
    @DisplayName("非精确触发光标回退到通配条目（种子→plant_crop）")
    void wildcardCatchesNonPreciseTriggers() {
        InteractionRegistry.register(WILDCARD_FARM);
        InteractionRegistry.register(PRECISE_BONEMEAL);

        ItemStack seeds = new ItemStack(Items.WHEAT_SEEDS);
        LivingItemManager.setLiving(seeds, true);

        InteractionEntry matched = find(seeds);
        assertEquals("plant_crop", matched.actionId(),
            "非骨粉光标应回退命中通配条目");
    }

    @Test
    @DisplayName("精确条目要求活触发器：非活骨粉回退通配条目")
    void preciseEntryRequiresLivingTrigger() {
        InteractionRegistry.register(WILDCARD_FARM);
        InteractionRegistry.register(PRECISE_BONEMEAL);

        ItemStack deadBoneMeal = new ItemStack(Items.BONE_MEAL);   // 未打 IS_LIVING
        InteractionEntry matched = find(deadBoneMeal);
        assertEquals("plant_crop", matched.actionId(),
            "非活骨粉不满足精确条目的活物品校验，应回退通配条目");
    }

    @Test
    @DisplayName("无任何条目匹配活物品组合 → null")
    void noMatchReturnsNull() {
        InteractionRegistry.register(WILDCARD_FARM);
        InteractionRegistry.register(PRECISE_BONEMEAL);

        ItemStack dirt = new ItemStack(Items.DIRT);
        LivingItemManager.setLiving(dirt, true);
        ItemStack target = new ItemStack(Items.CHEST);   // target 不匹配任何条目
        LivingItemManager.setLiving(target, true);

        assertNull(InteractionRegistry.findInteraction(dirt, target, 1, false));
    }

    @Test
    @DisplayName("非活目标物品不触发交互")
    void deadTargetNeverMatches() {
        InteractionRegistry.register(WILDCARD_FARM);
        InteractionRegistry.register(PRECISE_BONEMEAL);

        ItemStack boneMeal = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(boneMeal, true);
        ItemStack deadFarmland = new ItemStack(Items.FARMLAND);   // 未打 IS_LIVING

        assertNull(InteractionRegistry.findInteraction(boneMeal, deadFarmland, 1, false));
    }

    @Test
    @DisplayName("triggerFilter 组合过滤：种子数不足的光标不拦截")
    void triggerFilterGatesInterception() {
        // 复刻 plant_crop 生产注册：过滤 = 种子数 ≥ 耕地堆叠数
        InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, null, 1, "plant_crop",
            false, (trigger, target) -> PlantCropHandler.canPlantWith(trigger, target)));

        ItemStack farmland = new ItemStack(Items.FARMLAND, 16);
        LivingItemManager.setLiving(farmland, true);

        ItemStack fewSeeds = new ItemStack(Items.WHEAT_SEEDS, 5);    // 5 < 16
        LivingItemManager.setLiving(fewSeeds, true);
        assertNull(InteractionRegistry.findInteraction(fewSeeds, farmland, 1, false),
            "种子数不足 → 不拦截（原版交换照常）");

        ItemStack enoughSeeds = new ItemStack(Items.WHEAT_SEEDS, 16);
        LivingItemManager.setLiving(enoughSeeds, true);
        InteractionEntry matched = InteractionRegistry.findInteraction(enoughSeeds, farmland, 1, false);
        assertEquals("plant_crop", matched.actionId(),
            "种子数充足 → 拦截种植");
    }

    @Test
    @DisplayName("triggerFilter 对精确条目同样生效（数量门槛）")
    void triggerFilterAppliesToPreciseEntries() {
        // 精确条目 + 过滤（模拟骨粉若挂目标状态过滤的通用机制验证）
        InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, Items.BONE_MEAL, 1, "bonemeal",
            false, (trigger, target) -> target.getCount() == 1));

        ItemStack boneMeal = new ItemStack(Items.BONE_MEAL);
        LivingItemManager.setLiving(boneMeal, true);

        ItemStack single = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(single, true);
        InteractionEntry matched = InteractionRegistry.findInteraction(boneMeal, single, 1, false);
        assertEquals("bonemeal", matched.actionId());

        ItemStack stack16 = new ItemStack(Items.FARMLAND, 16);
        LivingItemManager.setLiving(stack16, true);
        assertNull(InteractionRegistry.findInteraction(boneMeal, stack16, 1, false),
            "过滤不命中的精确条目应被拒绝（而非静默吞点击）");
    }
}
