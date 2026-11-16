package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * {@link FluidSourcesSnapshot}（Q6）的守卫：物品侧流体组件值的不可变语义。
 *
 * <p>原始问题（TODO 3.3）：组件值曾是可变 {@code ContainerFluidData}（无 equals/hashCode）——
 * 开发期 {@code ItemStack.set} 抛 {@link IllegalArgumentException}、生产侧引用判等使物品不可堆叠。
 * 改 record 后两条都回归：真 set 不抛、按值判等。</p>
 */
class FluidSourcesSnapshotTest {

    private static FluidType water() {
        return Fluids.WATER.getFluidType();
    }

    private static FluidType lava() {
        return Fluids.LAVA.getFluidType();
    }

    private static String idOf(FluidType fluid) {
        return NeoForgeRegistries.FLUID_TYPES.getKey(fluid).toString();
    }

    private static ContainerFluidData dataWithTwoSources() {
        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(3, water());
        data.registerGeneratedSource(7, lava());
        return data;
    }

    @Test
    @DisplayName("前提：本环境开组件校验（否则「set 不抛」断言恒真、没有信息量）")
    void devValidationPremise() {
        assertTrue(SharedConstants.IS_RUNNING_IN_IDE,
            "FML 单测非生产环境 ⇒ validateComponent 必须启用，原始 bug 才能在此环境复现/回归");
    }

    @Test
    @DisplayName("真 ItemStack.set 不再抛校验异常；同组件按值判等（原始 bug 直接回归）")
    void realItemStackSet_doesNotThrow_andMatchesByValue() {
        FluidSourcesSnapshot snapshot = dataWithTwoSources().captureSources();

        ItemStack first = assertDoesNotThrow(() -> {
            ItemStack stack = new ItemStack(Items.SHULKER_BOX);
            stack.set(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get(), snapshot);
            return stack;
        }, "record 组件值必须通过开发期 validateComponent（原可变类在此抛 IllegalArgumentException）");

        ItemStack second = new ItemStack(Items.SHULKER_BOX);
        second.set(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get(), dataWithTwoSources().captureSources());
        assertTrue(ItemStack.matches(first, second), "同源的两次抓取必须按值判等（record equals）——"
            + "旧可变类引用判等恒不等，这是 TODO 3.3 ① 的生产后果");

        ItemStack different = new ItemStack(Items.SHULKER_BOX);
        different.set(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get(),
            new FluidSourcesSnapshot(List.of(new FluidSourceEntry(3, idOf(water())))));
        assertFalse(ItemStack.matches(first, different), "源不同 ⇒ 不得判等");
    }

    @Test
    @DisplayName("形状守卫：快照 CODEC 与 BE 附件 CODEC 对同一源的 NBT 完全一致（字段名 slot/fluid 锁定）")
    void codecShapeParity_withContainerFluidDataCodec() {
        ContainerFluidData data = dataWithTwoSources();

        Tag attachmentForm = ContainerFluidData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        FluidSourcesSnapshot snapshot = data.captureSources();
        Tag itemForm = FluidSourcesSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, snapshot).getOrThrow();

        assertEquals(attachmentForm, itemForm,
            "两条轨道共用 FluidSourceEntry ⇒ NBT 必须一致 —— 改字段名 = 旧物品组件数据全部解析失败");
    }

    @Test
    @DisplayName("历史形状守卫：旧物品组件数据的字面 NBT（slot/fluid 字段）必须照常解析")
    void legacyItemComponentNbt_stillParses() {
        // 2026-10-19 起 ContainerFluidData.CODEC 写出的形状 —— Q6 改 record 不得动它（零迁移）
        var list = new net.minecraft.nbt.ListTag();
        list.add(entryTag(3, "minecraft:water"));
        list.add(entryTag(7, "minecraft:lava"));

        FluidSourcesSnapshot parsed = FluidSourcesSnapshot.CODEC.parse(NbtOps.INSTANCE, list).getOrThrow();

        assertEquals(List.of(new FluidSourceEntry(3, "minecraft:water"),
            new FluidSourceEntry(7, "minecraft:lava")), parsed.sources(),
            "旧数据字段名/结构变化 ⇒ 玩家已有物品的流体静默蒸发（违反零迁移判据）");

        // 编码侧同形：条目只有 slot/fluid 两个键
        Tag encoded = FluidSourcesSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, parsed).getOrThrow();
        for (var t : (net.minecraft.nbt.ListTag) encoded) {
            var compound = (net.minecraft.nbt.CompoundTag) t;
            assertEquals(java.util.Set.of("slot", "fluid"), compound.getAllKeys(),
                "条目键集必须是历史形状 {slot, fluid}");
        }
    }

    private static net.minecraft.nbt.CompoundTag entryTag(int slot, String fluidId) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("slot", slot);
        tag.putString("fluid", fluidId);
        return tag;
    }

    @Test
    @DisplayName("快照 CODEC 往返：record 逐字段存活（含 EMPTY）")
    void codec_roundTrip() {
        FluidSourcesSnapshot snapshot = dataWithTwoSources().captureSources();

        Tag tag = FluidSourcesSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, snapshot).getOrThrow();
        assertEquals(snapshot, FluidSourcesSnapshot.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());

        Tag emptyTag = FluidSourcesSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, FluidSourcesSnapshot.EMPTY).getOrThrow();
        assertEquals(FluidSourcesSnapshot.EMPTY,
            FluidSourcesSnapshot.CODEC.parse(NbtOps.INSTANCE, emptyTag).getOrThrow());
    }

    @Test
    @DisplayName("fromSnapshot 每次建全新运行时对象：创造多次放置互不污染")
    void fromSnapshot_restoresIndependentRuntimeObjects() {
        FluidSourcesSnapshot snapshot = dataWithTwoSources().captureSources();

        ContainerFluidData first = ContainerFluidData.fromSnapshot(snapshot);
        ContainerFluidData second = ContainerFluidData.fromSnapshot(snapshot);

        assertNotEquals(first, second, "必须各建一份 —— 同一物品可连续放置多个容器");
        assertEquals(first.getGeneratedSources(), second.getGeneratedSources(), "初值一致");
        assertTrue(first.getFlows().isEmpty() && second.getFlows().isEmpty(),
            "流动表不进快照，还原后为空（下一 tick BFS 重算）");

        first.removeGeneratedSource(3);
        assertTrue(second.hasGeneratedSources() && second.sourceAt(3) != null,
            "改第一个容器不得影响第二个（旧共享可变对象会把水『同时出现/同时消失』）");
    }

    @Test
    @DisplayName("fromSnapshot 降级留痕：未知流体 id 安全丢弃（I-C6），其余源保留")
    void fromSnapshot_dropsUnknownFluidIds() {
        FluidSourcesSnapshot snapshot = new FluidSourcesSnapshot(List.of(
            new FluidSourceEntry(3, idOf(water())),
            new FluidSourceEntry(5, "living_item:not_a_real_fluid_type")));

        ContainerFluidData restored = ContainerFluidData.fromSnapshot(snapshot);

        assertEquals(java.util.Map.of(3, water()), restored.getGeneratedSources(),
            "未知 id 的源丢弃、已知源保留 —— 静默全丢会让源跨形态蒸发");
    }

    @Test
    @DisplayName("端到端：moveOut → 物品 → moveIn 守恒；物品 copy 后组件仍在且相等")
    void roundTrip_moveOutToItemToMoveIn_conservesSources() {
        ContainerFluidData data = dataWithTwoSources();
        BlockEntity be = mock(BlockEntity.class);
        org.mockito.Mockito.doReturn(BlockEntityType.SHULKER_BOX).when(be).getType();
        when(be.getData(WaterComponents.CONTAINER_FLUID_DATA)).thenReturn(data);

        ItemStack item = new ItemStack(Items.SHULKER_BOX);
        assertTrue(ContainerDataMigration.moveOut(be, item));
        assertTrue(data.isEmpty(), "宿主就地清空（防另一半再迁移 = 复制）");

        ItemStack copied = item.copy();
        FluidSourcesSnapshot carried = copied.get(WaterComponents.CONTAINER_FLUID_DATA_ITEM.get());
        assertNotNull(carried, "copy 后组件必须还在（随物品迁移的前提）");
        assertTrue(ItemStack.matches(item, copied), "copy 后整栈按值判等");

        BlockEntity newBe = mock(BlockEntity.class);
        org.mockito.Mockito.doReturn(BlockEntityType.SHULKER_BOX).when(newBe).getType();
        when(newBe.getLevel()).thenReturn(null);   // 无 Level ⇒ 走「只写附件」降级分支
        when(newBe.getData(WaterComponents.CONTAINER_FLUID_DATA)).thenReturn(null);

        ContainerDataMigration.moveIn(newBe, copied);

        org.mockito.ArgumentCaptor<ContainerFluidData> captor =
            org.mockito.ArgumentCaptor.forClass(ContainerFluidData.class);
        verify(newBe).setData(Mockito.eq(WaterComponents.CONTAINER_FLUID_DATA.value()), captor.capture());
        ContainerFluidData restored = captor.getValue();

        assertEquals(java.util.Map.of(3, water(), 7, lava()), restored.getGeneratedSources(),
            "破坏→放置后派生源必须逐条存活（含流体类型，不能岩浆退化成水）");
        assertTrue(restored.getFlows().isEmpty(), "流动表还原后为空，下一 tick BFS 重算");
    }

    @Test
    @DisplayName("EMPTY 快照：isEmpty 判据是值相等（回填/比较不因实例不同而失真）")
    void emptySnapshot_valueSemantics() {
        assertTrue(FluidSourcesSnapshot.EMPTY.isEmpty());
        assertEquals(FluidSourcesSnapshot.EMPTY, new FluidSourcesSnapshot(List.of()));
    }
}
