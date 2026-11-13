package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 活耕地功能测试 —— 输出合并回归守卫 + 堆叠判定守卫。
 *
 * <p>输出合并回归守卫（2026-09-14 终审）：生长槽有同种<b>部分堆叠</b>且剩余空间放不下整份产出时，
 * 旧实现塞入 {@code min(outputCount, space)} 即推进 outputIndex——
 * {@code outputCount - toAdd} 差额物品静默消失（玩家从生长槽拿走一半产物再等下一轮即触发）。
 * 修复：合并仅当 space ≥ outputCount，放不下就本 tick 等待（与「不同种占据」
 * 同一语义），零损失。</p>
 *
 * <p>堆叠判定守卫：生长节拍不写入物品组件，同种子同阶段的生长期耕地可叠；
 * 成熟待产出态因 pendingDrops/outputIndex 参与比较而保持不可叠。</p>
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
        return new FarmlandPlantComponent(Items.WHEAT_SEEDS, 7, 7, outputIndex,
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
            Items.NETHER_WART, 3, 3, 0,
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

    @Test
    @DisplayName("超堆叠生长槽（上限 512，已有 100）⇒ 整份并入到 164，不再被物品上限判满停摆")
    void superstackGrowthSlot_mergesBeyondItemMax() {
        int[] limits = new int[27];
        java.util.Arrays.fill(limits, 64);
        limits[1] = 512;
        FakeContainerContext ctx = boxCtx().withSlotLimits(limits);
        ItemStack farmland = new ItemStack(Items.FARMLAND, 64);
        ctx.set(1, new ItemStack(Items.WHEAT, 100));
        FarmlandPlantComponent plant = pendingWheat(0);

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.WHEAT);

        assertEquals(164, ctx.getItem(1).getCount(),
            "旧 getMaxStackSize()-count 口径下 100 ≥ 64 ⇒ 判满停摆（I-C14 ① 容量问容器）");
        assertEquals(1, out.outputIndex(), "索引正常推进");
    }

    @Test
    @DisplayName("小槽位上限：空槽产出 64 超上限 4 ⇒ 放 4 个 + 推进（WARN 留痕，不再靠 clamp 静默截断）")
    void smallSlotLimit_placesWhatFitsAndAdvances() {
        int[] limits = new int[27];
        java.util.Arrays.fill(limits, 64);
        limits[1] = 4;
        FakeContainerContext ctx = boxCtx().withSlotLimits(limits);
        ItemStack farmland = new ItemStack(Items.FARMLAND, 64);
        FarmlandPlantComponent plant = pendingWheat(0);

        FarmlandPlantComponent out = LivingFarmlandFunction.tryOutputOnce(ctx, 1, farmland, plant, Blocks.WHEAT);

        assertEquals(4, ctx.getItem(1).getCount(),
            "produce 以槽位上限为预算 —— 旧实现 setItem(64) 后被 clampToSlotLimit 静默截断");
        assertEquals(1, out.outputIndex(),
            "actual > 0 ⇒ 按已交付记账推进（差额有 WARN 留痕）");
    }

    @Test
    @DisplayName("同种子同阶段的两叠生长期耕地可堆叠（节拍不写入物品组件）")
    void sameCropSameAge_stacks() {
        FarmlandPlantComponent growing = new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 3, 7, -1, java.util.List.of());
        ItemStack a = livingFarmlandWith(growing);
        ItemStack b = livingFarmlandWith(growing);

        assertEquals(growing, FarmlandPlantComponent.of(b));
        assertTrue(ItemStack.isSameItemSameComponents(a, b),
            "同种子 / 同 age / 同 maxAge 的生长期耕地必须可堆叠");
    }

    @Test
    @DisplayName("不同生长阶段的耕地仍不可叠（作物状态参与比较）")
    void differentAge_doesNotStack() {
        ItemStack a = livingFarmlandWith(new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 3, 7, -1, java.util.List.of()));
        ItemStack b = livingFarmlandWith(new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 4, 7, -1, java.util.List.of()));

        assertFalse(ItemStack.isSameItemSameComponents(a, b),
            "age 是渲染数据源，不同阶段合并会吞掉生长进度");
    }

    @Test
    @DisplayName("成熟待产出的耕地与生长期耕地不可堆叠（冻结产物不参与合并）")
    void frozenMature_doesNotStackWithGrowing() {
        ItemStack growing = livingFarmlandWith(new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 3, 7, -1, java.util.List.of()));
        ItemStack frozen = livingFarmlandWith(new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 7, 7, 0,
            java.util.List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT_SEEDS))));

        assertFalse(ItemStack.isSameItemSameComponents(growing, frozen),
            "成熟待产出态必须保持不可叠，否则合并会吞掉冻结的战利品");
    }

    @Test
    @DisplayName("旧存档组件含 last_growth_attempt_tick ⇒ 多余键被忽略，解码不失败")
    void legacyCodec_ignoresRemovedTimestamp() {
        CompoundTag tag = new CompoundTag();
        tag.putString("crop_seed", "minecraft:wheat_seeds");
        tag.putInt("age", 3);
        tag.putInt("max_age", 7);
        tag.putLong("last_growth_attempt_tick", 12345L);
        tag.putInt("output_index", -1);
        tag.put("pending_drops", new ListTag());

        var parsed = FarmlandPlantComponent.CODEC.parse(NbtOps.INSTANCE, tag);

        assertTrue(parsed.result().isPresent(), "旧格式多余键应被忽略");
        assertEquals(3, parsed.result().orElseThrow().age());
    }

    private static ItemStack livingFarmlandWith(FarmlandPlantComponent plant) {
        ItemStack stack = new ItemStack(Items.FARMLAND, 1);
        LivingItemManager.setLiving(stack, true);
        FarmlandPlantComponent.set(stack, plant);
        return stack;
    }
}
