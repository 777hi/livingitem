package com.qiqi.li.living.domain.furnace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.living.domain.furnace.FurnaceSegment.FurnaceRuntime;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.domain.furnace.FurnaceSegment.FurnaceRuntime;

/**
 * 熔炉输出槽判据与配方缓存回归（2026-10-27 审查 P1-2 / P2-3）。
 *
 * <p><b>守什么</b>：</p>
 * <ul>
 *   <li><b>预检 ↔ 写入同源</b>（P1-2）：预检曾只比物品 ID，写入走
 *       {@code isSameItemSameComponents} ⇒ 输出槽放「同物品不同组件」（改名铁锭 /
 *       活铁锭）时预检放行、完成写不进 ⇒ 进度卡 isComplete 不复位、燃料无限烧、零产出。
 *       原版 {@code canBurn} 用组件级判据，同等场景根本不点燃。</li>
 *   <li><b>无配方缓存生效</b>（P2-3）：命中快路径曾只认 cachedResult==1，无配方写入又被
 *       inputItem 非空门控挡住 ⇒ 非熔炼物品每 tick 重复查 RecipeManager
 *       （§8.5「相同输入只查一次」失效）。</li>
 * </ul>
 */
class FurnaceOutputGateTest {

    // ⚠️ 槽位按 DEFAULT_FURNACE 的网格邻居语义布置（furnace 在 4，宽 9）：
    // input=LEFT→3 / output=RIGHT→5 / fuel=DOWN→13（SlotResolver：行 = base/9 + dy）
    private static final int FURNACE_SLOT = 4;
    private static final int INPUT_SLOT = 3;
    private static final int OUTPUT_SLOT = 5;
    private static final int FUEL_SLOT = 13;

    private RecipeManager recipeManager;

    @AfterEach
    void tearDown() {
        ContainerRuntimeCache.clear();
    }

    /** 服务端 Level + 可控的熔炉配方表。 */
    private Level levelWithRecipe(boolean hasRecipe) {
        Level level = org.mockito.Mockito.mock(Level.class);
        recipeManager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipeManager);
        // ⚠️ getRecipeFor 的泛型（RecipeType<T> ⇒ RecipeHolder<T>）用 doReturn 绕过类型检查
        org.mockito.Mockito.doReturn(hasRecipe
                ? Optional.of(new RecipeHolder<>(
                    ResourceLocation.withDefaultNamespace("test_smelt"), mockRecipe()))
                : Optional.empty())
            .when(recipeManager)
            .getRecipeFor(eq(RecipeType.SMELTING), ArgumentMatchers.<SingleRecipeInput>any(), eq(level));
        return level;
    }

    private static Recipe<?> mockRecipe() {
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getResultItem(any())).thenReturn(new ItemStack(Items.IRON_INGOT));
        return recipe;
    }

    private static ItemStack livingFurnace() {
        ItemStack stack = new ItemStack(Items.FURNACE);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static FakeContainerContext furnaceCtx(ItemStack input, ItemStack output, ItemStack fuel) {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FURNACE_SLOT, livingFurnace());
        ctx.set(INPUT_SLOT, input);
        ctx.set(OUTPUT_SLOT, output);
        ctx.set(FUEL_SLOT, fuel);
        return ctx;
    }

    /** transform 真身 = 运行时缓存（组件里的会被方向初始化重置，别读错）。 */
    private static TransformData runtimeTransform(FakeContainerContext ctx) {
        FurnaceRuntime fr = ContainerRuntimeCache.get(ctx, FURNACE_SLOT).get(FurnaceSegment.INSTANCE);
        return fr == null || fr.transform() == null ? TransformData.EMPTY : fr.transform();
    }

    private static void tickOnce(FakeContainerContext ctx, Level level) {
        new LivingFurnaceFunction().tick(
            List.of(new LivingItemFunction.SlotEntry(FURNACE_SLOT, ctx.getItem(FURNACE_SLOT))),
            ctx, new TickContext(ctx), level);
    }

    /** 预置「正在燃烧 + 进度临门」的运行时缓存 —— 绕开测试环境无燃料价值表的限制。 */
    private static FakeContainerContext seededCtx(ItemStack input, ItemStack output) {
        FakeContainerContext ctx = new FakeContainerContext(27, 9);
        ctx.set(FURNACE_SLOT, livingFurnace());
        ctx.set(INPUT_SLOT, input);
        ctx.set(OUTPUT_SLOT, output);
        ctx.set(FUEL_SLOT, new ItemStack(Items.COAL, 8));
        ContainerRuntimeCache.update(ctx, FURNACE_SLOT,
            RuntimeSegments.EMPTY.with(FurnaceSegment.INSTANCE,
                new FurnaceRuntime(199, 200, 1600, null)));   // 燃烧中 + 差 1 tick 完成
        return ctx;
    }

    @Test
    @DisplayName("输出槽同物品不同组件（改名铁锭）⇒ 预检不放行：进度回退、不消耗输入、产物不混写")
    void componentMismatchedOutput_doesNotExecute() {
        ItemStack renamed = new ItemStack(Items.IRON_INGOT);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("renamed"));
        FakeContainerContext ctx = seededCtx(new ItemStack(Items.RAW_IRON), renamed);
        Level level = levelWithRecipe(true);

        tickOnce(ctx, level);

        assertEquals(1, ctx.getItem(OUTPUT_SLOT).getCount(),
            "产物不得混进组件不同的堆（写入判据 isSameItemSameComponents）");
        assertEquals(1, ctx.getItem(INPUT_SLOT).getCount(), "输入不消耗");
        assertTrue(runtimeTransform(ctx).cachedResult() == 1, "配方缓存正常");
        FurnaceRuntime fr = ContainerRuntimeCache.get(ctx, FURNACE_SLOT).get(FurnaceSegment.INSTANCE);
        assertTrue(fr.progress() < 200,
            "预检不放行 ⇒ 进度回退（旧实现预检放行、完成写不进 ⇒ 进度卡 isComplete 不复位"
            + "、燃料无限烧、零产出），got=" + fr.progress());
    }

    @Test
    @DisplayName("正向控制：输出槽空 ⇒ 预检放行，完成即产出（预检放行 ⇒ 完成必可写入）")
    void emptyOutput_executesNormally() {
        FakeContainerContext ctx = seededCtx(new ItemStack(Items.RAW_IRON), ItemStack.EMPTY);
        Level level = levelWithRecipe(true);

        tickOnce(ctx, level);

        assertTrue(ctx.getItem(OUTPUT_SLOT).is(Items.IRON_INGOT),
            "产物应写入输出槽（mock 配方：RAW_IRON → IRON_INGOT）");
        assertEquals(1, ctx.getItem(OUTPUT_SLOT).getCount());
        assertEquals(0, ctx.getItem(INPUT_SLOT).getCount(), "输入消耗 1 个");
    }

    @Test
    @DisplayName("非熔炼物品（无配方）⇒ 配方只查一次（无配方缓存生效，P2-3）")
    void noRecipeQueriedOnce() {
        FakeContainerContext ctx = seededCtx(new ItemStack(Items.DIRT), ItemStack.EMPTY);
        Level level = levelWithRecipe(false);

        tickOnce(ctx, level);
        tickOnce(ctx, level);

        verify(recipeManager, times(1))
            .getRecipeFor(eq(RecipeType.SMELTING), ArgumentMatchers.<SingleRecipeInput>any(), any(Level.class));
        // transform 真身在运行时缓存（组件里的会被方向初始化重置，别读错）
        TransformData tf = runtimeTransform(ctx);
        assertEquals(0, tf.cachedResult(), "无配方应缓存为 0");
        assertEquals("minecraft:dirt", tf.cachedInput());
    }
}
