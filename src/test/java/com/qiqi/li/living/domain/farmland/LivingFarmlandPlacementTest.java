package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.qiqi.li.living.api.LivingItemManager;

/**
 * 活耕地「放置回世界」回归守卫（2026-09-16）。
 *
 * <p>语义：放置已种植的活耕地时，<b>模拟玩家拿种子右键这块耕地</b>种一次。种植是
 * <b>软逻辑</b>——能种上就好，种不上（含抛异常）绝不能影响原版放置流程，故有
 * 「异常不冒泡」这条红线用例。</p>
 *
 * <p>测试边界：{@code useOn} 之后的成败判定属原版/模组职责，交由游戏实测覆盖；
 * 这里只钉住我们自己引入的 4 条不变量：触发条件、客户端不生效、落点在耕地之上且
 * 一律幼苗、异常不外泄。</p>
 */
class LivingFarmlandPlacementTest {

    private static final BlockPos FARMLAND_POS = new BlockPos(10, 64, 10);
    private static final BlockPos CROP_POS = FARMLAND_POS.above();

    /** 活耕地（带 IS_LIVING + FARMLAND_PLANT 组件），age 刻意给 5 以证明「不保留成熟度」 */
    private static ItemStack plantedLivingFarmland() {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        LivingItemManager.setFarmlandPlant(stack, new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 5, 7, 0L, -1, List.of()));
        return stack;
    }

    /** 落点（耕地）与作物位（空气）都可用的 Level 替身 */
    private static ServerLevel mockLevel() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.isClientSide()).thenReturn(false);
        Mockito.when(level.enabledFeatures()).thenReturn(FeatureFlags.VANILLA_SET);
        Mockito.when(level.getBlockState(FARMLAND_POS)).thenReturn(Blocks.FARMLAND.defaultBlockState());
        Mockito.when(level.getBlockState(CROP_POS)).thenReturn(Blocks.AIR.defaultBlockState());
        Mockito.when(level.isUnobstructed(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(true);
        Mockito.when(level.setBlock(Mockito.any(), Mockito.any(), Mockito.anyInt())).thenReturn(true);
        // CropBlock.canSurvive → hasSufficientLight：要求亮度 ≥ 8，否则作物种不下
        Mockito.when(level.getRawBrightness(Mockito.any(BlockPos.class), Mockito.anyInt())).thenReturn(9);
        return level;
    }

    /**
     * 端到端用替身：{@code FARMLAND_POS} 放置前是空气、放置后变耕地——
     * 用 {@code thenAnswer} 模拟真实状态迁移（固定 stub 过不了 {@code canPlace}）。
     */
    private static ServerLevel mockLevelWithPlacement() {
        ServerLevel level = mockLevel();
        AtomicBoolean placed = new AtomicBoolean(false);
        Mockito.when(level.getBlockState(FARMLAND_POS)).thenAnswer(inv ->
            placed.get() ? Blocks.FARMLAND.defaultBlockState() : Blocks.AIR.defaultBlockState());
        Mockito.when(level.setBlock(Mockito.any(), Mockito.any(), Mockito.anyInt())).thenAnswer(inv -> {
            if (FARMLAND_POS.equals(inv.getArgument(0)) && inv.<BlockState>getArgument(1).is(Blocks.FARMLAND)) {
                placed.set(true);
            }
            return true;
        });
        return level;
    }

    // ---------- 触发条件 ----------

    @Test
    @DisplayName("isPlantable 真值表：仅「活耕地 + 已种植」为真")
    void isPlantable_truthTable() {
        assertFalse(LivingFarmlandPlacement.isPlantable(new ItemStack(Items.FARMLAND)),
            "非活耕地 → false");

        ItemStack livingUnplanted = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(livingUnplanted, true);
        assertFalse(LivingFarmlandPlacement.isPlantable(livingUnplanted),
            "活但未种植 → false");

        assertTrue(LivingFarmlandPlacement.isPlantable(plantedLivingFarmland()),
            "活 + 已种植 → true");

        ItemStack livingDirt = new ItemStack(Items.DIRT, 1);
        LivingItemManager.setLiving(livingDirt, true);
        LivingItemManager.setFarmlandPlant(livingDirt, new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 5, 7, 0L, -1, List.of()));
        assertFalse(LivingFarmlandPlacement.isPlantable(livingDirt),
            "非耕地（即使带种植组件）→ false");
    }

    // ---------- 落点与成熟度 ----------

    @Test
    @DisplayName("已种植活耕地放置 → 在耕地「上方一格」种下幼苗（age 0，不保留成熟度）")
    void onPlaced_plantsSeedlingAboveFarmland() {
        ServerLevel level = mockLevel();

        LivingFarmlandPlacement.onPlaced(level, FARMLAND_POS, plantedLivingFarmland(), null);

        ArgumentCaptor<BlockState> state = ArgumentCaptor.forClass(BlockState.class);
        Mockito.verify(level).setBlock(Mockito.eq(CROP_POS), state.capture(), Mockito.eq(11));
        assertEquals(Blocks.WHEAT, state.getValue().getBlock(), "种的是种子对应的作物方块");
        assertEquals(0, state.getValue().getValue(CropBlock.AGE),
            "一律幼苗——物品里的 age=5 不保留");
    }

    // ---------- Mixin 接线（端到端） ----------

    @Test
    @DisplayName("端到端：BlockItem.place 放置已种植活耕地 → 自动种下作物"
        + "（同时钉住「必须注入在 consume 之前」——注在 RETURN 会因空栈读不到组件而失效）")
    void placeViaBlockItem_plantsCrop() {
        ServerLevel level = mockLevelWithPlacement();
        BlockPos groundPos = FARMLAND_POS.below();
        Mockito.when(level.getBlockState(groundPos)).thenReturn(Blocks.DIRT.defaultBlockState());

        // 玩家右键地面：耕地会落在 groundPos.above() = FARMLAND_POS
        ItemStack farmland = plantedLivingFarmland();
        BlockPlaceContext context = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, farmland,
            new BlockHitResult(Vec3.atCenterOf(groundPos), Direction.UP, groundPos, false));

        ((BlockItem) Items.FARMLAND).place(context);

        ArgumentCaptor<BlockState> state = ArgumentCaptor.forClass(BlockState.class);
        Mockito.verify(level).setBlock(Mockito.eq(CROP_POS), state.capture(), Mockito.eq(11));
        assertEquals(Blocks.WHEAT, state.getValue().getBlock(),
            "Mixin 已接线：放置活耕地后自动种下了种子对应的作物");
    }

    // ---------- 软逻辑红线 ----------

    @Test
    @DisplayName("客户端（isClientSide）→ 完全不动")
    void onPlaced_clientSide_noOp() {
        ServerLevel level = mockLevel();
        Mockito.when(level.isClientSide()).thenReturn(true);

        LivingFarmlandPlacement.onPlaced(level, FARMLAND_POS, plantedLivingFarmland(), null);

        Mockito.verify(level, Mockito.never()).setBlock(Mockito.any(), Mockito.any(), Mockito.anyInt());
    }

    @Test
    @DisplayName("软逻辑红线：种植过程抛异常 → 被吞掉、不外泄、不影响原版放置")
    void onPlaced_exceptionIsSwallowed() {
        ServerLevel level = mockLevel();
        Mockito.when(level.getBlockState(Mockito.any(BlockPos.class)))
            .thenThrow(new RuntimeException("模拟模组种植逻辑炸了"));

        assertDoesNotThrow(
            () -> LivingFarmlandPlacement.onPlaced(level, FARMLAND_POS, plantedLivingFarmland(), null),
            "异常冒泡出 BlockItem.place 会造成「方块已放、物品未扣」并炸 tick");
        Mockito.verify(level, Mockito.never()).setBlock(Mockito.any(), Mockito.any(), Mockito.anyInt());
    }
}
