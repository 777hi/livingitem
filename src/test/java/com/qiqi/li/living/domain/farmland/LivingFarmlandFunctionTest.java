package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.testutil.FakeContainerContext;

/**
 * tryOutputOnce 输出合并回归守卫 —— 2026-09-14 终审发现的部分合并丢物品：
 *
 * <p>生长槽有同种<b>部分堆叠</b>且剩余空间放不下整份产出时，旧实现塞入
 * {@code min(outputCount, space)} 即推进 outputIndex——{@code outputCount - toAdd}
 * 差额物品静默消失（玩家从生长槽拿走一半产物再等下一轮即触发）。
 * 修复：合并仅当 space ≥ outputCount，放不下就本 tick 等待（与「不同种占据」
 * 同一语义），零损失。</p>
 *
 * <p>纯逻辑测试：tryOutputOnce 不依赖 Level（只有 ctx 读写），FakeContainerContext
 * 内存数组即可驱动。产出来源（小麦 = 64 耕地堆叠 × 1 麦 = 64 份产出恰好整组）。</p>
 */
class LivingFarmlandFunctionTest {

    /** 27 槽 9 宽（箱子布局），槽 4 正上方是槽 1（同一列上一行） */
    private static FakeContainerContext boxCtx() {
        return new FakeContainerContext(27, 9);
    }

    private static FarmlandPlantComponent pendingWheat(int outputIndex) {
        // 小麦成熟冻结：产出 [麦×1, 种子×1]（留种后），outputIndex 待注入
        return new FarmlandPlantComponent(Items.WHEAT_SEEDS, 7, 7, 0L, outputIndex,
            java.util.List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT_SEEDS)));
    }

    @Test
    @DisplayName("部分合并：生长槽同种半堆放不下整份 → 本 tick 等待，槽内数量与索引都不动")
    void partialMerge_waitsInsteadOfDroppingItems() {
        FakeContainerContext ctx = boxCtx();
        ItemStack farmland = new ItemStack(Items.FARMLAND, 64);
        // 生长槽已有 10 个小麦（玩家取走了一半），剩余空间 54 < 整份产出 64
        ctx.set(1, new ItemStack(Items.WHEAT, 10));
        FarmlandPlantComponent plant = pendingWheat(0);

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.WHEAT);

        assertEquals(10, ctx.getItem(1).getCount(), "放不下整份 → 槽内数量原样等待");
        assertEquals(0, out.outputIndex(), "索引不推进（旧实现此处推进 → 54 份麦凭空消失）");
        assertEquals(plant, out, "组件原样返回（无变化不写不同步）");
    }

    @Test
    @DisplayName("整份合并：空间足够 → 正常合并并推进索引")
    void fullMerge_proceeds() {
        FakeContainerContext ctx = boxCtx();
        ItemStack farmland = new ItemStack(Items.FARMLAND, 64);
        // 生长槽 0 个麦（空位 64）恰好放下整份 64
        ctx.set(1, new ItemStack(Items.WHEAT, 0));
        FarmlandPlantComponent plant = pendingWheat(0);

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.WHEAT);

        assertEquals(64, ctx.getItem(1).getCount(), "空堆 → 整份放入");
        assertEquals(1, out.outputIndex(), "索引推进到下一项（种子）");
    }

    @Test
    @DisplayName("空槽：整份直接放入 + 索引推进（基础路径无回归）")
    void emptySlot_placesFullOutput() {
        FakeContainerContext ctx = boxCtx();
        ItemStack farmland = new ItemStack(Items.FARMLAND, 64);
        FarmlandPlantComponent plant = pendingWheat(0);

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.WHEAT);

        assertEquals(64, ctx.getItem(1).getCount(), "空槽 → 整份 64（64 耕地 × 1 麦）");
        assertEquals(1, out.outputIndex());
    }

    @Test
    @DisplayName("产出量超出物品堆叠上限 → 钳制到 maxStackSize（count×count 大堆场景）")
    void outputClampedToMaxStackSize() {
        FakeContainerContext ctx = boxCtx();
        ItemStack farmland = new ItemStack(Items.FARMLAND, 64);
        // 下界疣式：堆叠上限 64、单份产出 4 × 64 耕地 = 256 → 钳制 64
        FarmlandPlantComponent plant = new FarmlandPlantComponent(
            Items.NETHER_WART, 3, 3, 0L, 0,
            java.util.List.of(new ItemStack(Items.NETHER_WART, 4)));

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.NETHER_WART);

        assertEquals(64, ctx.getItem(1).getCount(), "256 钳制到一组 64");
        assertEquals(-1, out.outputIndex(), "唯一产出项放完 → finishIfDone 重置（age 回退重长）");
        assertTrue(out.pendingDrops().isEmpty(), "待输出清空");
    }

    // Blocks 别名（避免逐处全限定）
    private static final class Blocks {
        static final net.minecraft.world.level.block.Block WHEAT = net.minecraft.world.level.block.Blocks.WHEAT;
        static final net.minecraft.world.level.block.Block NETHER_WART = net.minecraft.world.level.block.Blocks.NETHER_WART;
    }

    @Test
    @DisplayName("空间恰好等于整份（space == outputCount）→ 合并成功（边界含等号）")
    void exactSpace_merges() {
        FakeContainerContext ctx = boxCtx();
        ItemStack farmland = new ItemStack(Items.FARMLAND, 32);
        // 整份产出 = 32 耕地 × 1 麦 = 32；生长槽已有 32（空位恰 32）→ 放得下
        ctx.set(1, new ItemStack(Items.WHEAT, 32));
        FarmlandPlantComponent plant = pendingWheat(0);

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.WHEAT);

        assertEquals(64, ctx.getItem(1).getCount(), "32 + 32 恰好合并成整组");
        assertEquals(1, out.outputIndex());
        assertTrue(ctx.getItem(1).getCount() <= ctx.getItem(1).getMaxStackSize(), "不越堆叠上限");
    }
}
