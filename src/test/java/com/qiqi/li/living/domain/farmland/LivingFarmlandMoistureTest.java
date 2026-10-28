package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.FluidPresence;
import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 活耕地**湿润 BFS 与生长冷却门**（2026-10-28 补，审查测试缺口 #4）。
 *
 * <p>既有 `LivingFarmlandFunctionTest` 只覆盖 `tryOutputOnce`（产出合并丢物品），
 * 而「水源 → 相邻耕地 4 级 → 沿耕地每跳 -1」的传播、以及 200t 冷却门从未被测。
 * 两者都在 `tick()` 里，且 `computeMoisture` 是私有 ⇒ 走完整 tick + 假 FluidPresence。</p>
 *
 * <p>布局（27 槽 / 9 宽）：槽 1 是水；槽 0 与它相邻 ⇒ 4 级；槽 9 在 0 下方 ⇒ 3 级；
 * 槽 18 在 9 下方 ⇒ 2 级；槽 8 远离水源 ⇒ 干燥。</p>
 */
class LivingFarmlandMoistureTest {

    private static final int SIZE = 27;
    private static final int WIDTH = 9;

    private static ItemStack livingFarmland() {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static FakeContainerContext box() {
        return new FakeContainerContext(SIZE, WIDTH);
    }

    /** 只声明「哪些槽有水」的存在性视图（不构造整个流体账本）。 */
    private static TickContext tickWithWaterAt(int... fluidSlots) {
        FluidPresence presence = new FluidPresence() {
            @Override
            public boolean isEmpty() {
                return fluidSlots.length == 0;
            }

            @Override
            public boolean hasFluidAt(int slot) {
                for (int s : fluidSlots) {
                    if (s == slot) return true;
                }
                return false;
            }
        };
        TickContext tick = mock(TickContext.class);
        when(tick.getFluidPresence()).thenReturn(presence);
        return tick;
    }

    private static ServerLevel levelAt(long gameTime) {
        ServerLevel level = mock(ServerLevel.class);
        setRandom(level, RandomSource.create(1234L));
        when(level.getGameTime()).thenReturn(gameTime);
        return level;
    }

    /** {@code Level.random} 是字段（不是 getter）⇒ 只能反射塞。 */
    private static void setRandom(ServerLevel level, RandomSource random) {
        try {
            Field field = Level.class.getDeclaredField("random");
            field.setAccessible(true);
            field.set(level, random);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法给 ServerLevel 替身注入 random", e);
        }
    }

    private static void tickFarmland(FakeContainerContext ctx, ServerLevel level,
                                     TickContext tick, int... farmlandSlots) {
        List<SlotEntry> entries = new ArrayList<>();
        for (int slot : farmlandSlots) {
            entries.add(new SlotEntry(slot, ctx.getItem(slot)));
        }
        new LivingFarmlandFunction().tick(entries, ctx, tick, level);
    }

    @Test
    @DisplayName("水源相邻的耕地 4 级，沿耕地每跳 -1（0→9→18 全湿，远处 8 保持干燥）")
    void moisturePropagatesAlongFarmlandChain() {
        FakeContainerContext ctx = box();
        for (int slot : new int[] {0, 8, 9, 18}) {
            ctx.set(slot, livingFarmland());
        }
        ServerLevel level = levelAt(0);
        TickContext tick = tickWithWaterAt(1);

        tickFarmland(ctx, level, tick, 0, 8, 9, 18);

        assertTrue(FarmlandComponents.isMoist(ctx.getItem(0)), "紧邻水源的耕地应湿润（4 级源）");
        assertTrue(FarmlandComponents.isMoist(ctx.getItem(9)), "下游一格应湿润（3 级）");
        assertTrue(FarmlandComponents.isMoist(ctx.getItem(18)), "再下游一格应湿润（2 级）");
        assertFalse(FarmlandComponents.isMoist(ctx.getItem(8)), "远离水源的耕地必须保持干燥");
    }

    @Test
    @DisplayName("非活耕地阻断传播：隔着一块普通耕地，下游仍是干燥")
    void plainFarmlandBlocksPropagation() {
        FakeContainerContext ctx = box();
        ctx.set(0, livingFarmland());
        ctx.set(9, new ItemStack(Items.FARMLAND, 1));   // 普通耕地（未活化）作阻断
        ctx.set(18, livingFarmland());
        ServerLevel level = levelAt(0);
        TickContext tick = tickWithWaterAt(1);

        tickFarmland(ctx, level, tick, 0, 18);

        assertTrue(FarmlandComponents.isMoist(ctx.getItem(0)), "近水源的活耕地仍应湿润");
        assertFalse(FarmlandComponents.isMoist(ctx.getItem(18)),
            "普通耕地不参与传播，不能借它传导到下一格");
    }

    @Test
    @DisplayName("生长冷却门未到期（< 200t）⇒ 不写组件（稳态零写入）")
    void growthGate_closed_doesNotWrite() {
        FakeContainerContext ctx = box();
        ItemStack farmland = livingFarmland();
        FarmlandPlantComponent.set(farmland,
            new FarmlandPlantComponent(Items.WHEAT_SEEDS, 3, 7, 0L, -1, List.of()));
        ctx.set(9, farmland);
        ServerLevel level = levelAt(100);   // now - last(0) = 100 < 200
        TickContext tick = tickWithWaterAt();

        tickFarmland(ctx, level, tick, 9);

        assertEquals(0L, FarmlandPlantComponent.of(ctx.getItem(9)).lastGrowthAttemptTick(),
            "未到期不得推进时间戳（否则每 tick 都变成「刚试过」，永远轮不到生长）");
    }

    @Test
    @DisplayName("生长冷却门到期（≥ 200t）⇒ 推进时间戳（概率失败不改 age）")
    void growthGate_open_stampsAttempt() {
        FakeContainerContext ctx = box();
        ItemStack farmland = livingFarmland();
        FarmlandPlantComponent.set(farmland,
            new FarmlandPlantComponent(Items.WHEAT_SEEDS, 3, 7, 0L, -1, List.of()));
        ctx.set(9, farmland);

        ServerLevel level = mock(ServerLevel.class);
        RandomSource neverGrow = mock(RandomSource.class);
        when(neverGrow.nextInt(anyInt())).thenReturn(1);   // != 0 ⇒ 概率判定失败
        setRandom(level, neverGrow);
        when(level.getGameTime()).thenReturn(200L);
        TickContext tick = tickWithWaterAt();

        tickFarmland(ctx, level, tick, 9);

        FarmlandPlantComponent after = FarmlandPlantComponent.of(ctx.getItem(9));
        assertEquals(200L, after.lastGrowthAttemptTick(), "到期后应记下这次尝试");
        assertEquals(3, after.age(), "概率失败不得推进 age");
    }
}
