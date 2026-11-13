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
 * 活耕地**湿润 BFS 与生长边沿门**（2026-10-28 补，审查测试缺口 #4）。
 *
 * <p>既有 `LivingFarmlandFunctionTest` 只覆盖 `tryOutputOnce`（产出合并丢物品），
 * 而「水源 → 相邻耕地 4 级 → 沿耕地每跳 -1」的传播、以及世界轴 200t 边沿门从未被测。
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

    /** 指定概率结果的 Level 替身：roll=0 必定成功，非 0 必定失败。 */
    private static ServerLevel levelWithRoll(long gameTime, int roll) {
        ServerLevel level = mock(ServerLevel.class);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(anyInt())).thenReturn(roll);
        setRandom(level, random);
        when(level.getGameTime()).thenReturn(gameTime);
        return level;
    }

    private static FarmlandPlantComponent growingWheat() {
        return new FarmlandPlantComponent(Items.WHEAT_SEEDS, 3, 7, -1, List.of());
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
    @DisplayName("非节拍刻度（now % 200 != 0）⇒ 不进概率判定，组件零写入")
    void growthGate_offBeat_noProbabilityRoll() {
        FakeContainerContext ctx = box();
        ItemStack farmland = livingFarmland();
        FarmlandPlantComponent before = growingWheat();
        FarmlandPlantComponent.set(farmland, before);
        ctx.set(9, farmland);
        ServerLevel level = levelWithRoll(100, 0);   // 概率必定成功
        TickContext tick = tickWithWaterAt();

        tickFarmland(ctx, level, tick, 9);

        assertEquals(before, FarmlandPlantComponent.of(ctx.getItem(9)),
            "非刻度 tick 不得改组件（节拍由世界时间现算，不写入物品）");
        assertEquals(3, FarmlandPlantComponent.of(ctx.getItem(9)).age(),
            "即便概率必定成功也不得生长 ⇒ 门没有放行");
    }

    @Test
    @DisplayName("节拍刻度（now % 200 == 0）+ 概率失败 ⇒ 组件零写入")
    void growthGate_onBeat_probabilityFailureNoWrite() {
        FakeContainerContext ctx = box();
        ItemStack farmland = livingFarmland();
        FarmlandPlantComponent before = growingWheat();
        FarmlandPlantComponent.set(farmland, before);
        ctx.set(9, farmland);
        ServerLevel level = levelWithRoll(200, 1);   // != 0 ⇒ 概率判定失败

        tickFarmland(ctx, level, tickWithWaterAt(), 9);

        assertEquals(before, FarmlandPlantComponent.of(ctx.getItem(9)),
            "概率失败 ⇒ 不写不同步");
        assertEquals(3, FarmlandPlantComponent.of(ctx.getItem(9)).age());
    }

    @Test
    @DisplayName("节拍刻度 + 概率成功 ⇒ age+1（门确实放行）")
    void growthGate_onBeat_probabilitySuccessGrows() {
        FakeContainerContext ctx = box();
        ItemStack farmland = livingFarmland();
        FarmlandPlantComponent.set(farmland, growingWheat());
        ctx.set(9, farmland);
        ServerLevel level = levelWithRoll(200, 0);

        tickFarmland(ctx, level, tickWithWaterAt(), 9);

        assertEquals(4, FarmlandPlantComponent.of(ctx.getItem(9)).age(),
            "边界 tick 必须进入概率判定");
    }
}
