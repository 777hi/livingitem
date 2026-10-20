package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * {@link ContainerDataMigration} 的纯逻辑守卫（2026-10-19）。
 *
 * <p>守的是「破坏方块时把流体数据交给**正确的那件掉落物**」——交给错的那件，
 * 数据就跟着一个无关物品漂走，等于丢失。</p>
 *
 * <p>事件本身（{@code BlockDropsEvent} / {@code EntityPlaceEvent}）需要服务端环境，
 * 留给真机；这里只钉可独立验证的判据。</p>
 */
class ContainerDataMigrationTest {

    private static BlockEntity beOf(net.minecraft.world.level.block.Block block) {
        BlockEntity be = mock(BlockEntity.class);
        when(be.getBlockState()).thenReturn(block.defaultBlockState());
        return be;
    }

    private static ItemEntity dropOf(net.minecraft.world.item.Item item) {
        ItemEntity drop = mock(ItemEntity.class);
        when(drop.getItem()).thenReturn(new ItemStack(item));
        return drop;
    }

    @Test
    @DisplayName("方块掉本体 + 附加物：数据交给本体那件（不是第一件）")
    void pickDrop_choosesBlockItemNotFirstDrop() {
        BlockEntity be = beOf(Blocks.CHEST);
        ItemEntity dirt = dropOf(Items.DIRT);      // 附加物排在前面
        ItemEntity chest = dropOf(Items.CHEST);

        assertEquals(chest, ContainerDataMigration.pickDrop(List.of(dirt, chest), be),
            "应命中与该方块对应的物品，而不是列表首项");
    }

    @Test
    @DisplayName("只掉本体：直接命中")
    void pickDrop_singleDrop() {
        BlockEntity be = beOf(Blocks.SHULKER_BOX);
        ItemEntity only = dropOf(Items.SHULKER_BOX);

        assertEquals(only, ContainerDataMigration.pickDrop(List.of(only), be));
    }

    @Test
    @DisplayName("掉落物里没有本体（如被精准采集替换）：退回第一件，宁可给错也不丢")
    void pickDrop_noMatchingItem_fallsBackToFirst() {
        BlockEntity be = beOf(Blocks.CHEST);
        ItemEntity other = dropOf(Items.DIRT);

        assertEquals(other, ContainerDataMigration.pickDrop(List.of(other), be));
    }

    @Test
    @DisplayName("没有掉落物 → null（数据随 BE 消失，不抛）")
    void pickDrop_empty_returnsNull() {
        assertNull(ContainerDataMigration.pickDrop(List.of(), beOf(Blocks.CHEST)));
    }

    @Test
    @DisplayName("组件 CODEC 往返：派生源能在「BE 附件 → 物品组件 → BE 附件」中存活")
    void codec_roundTrip_preservesSources() {
        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(3, net.minecraft.world.level.material.Fluids.WATER.getFluidType());
        data.registerGeneratedSource(7, net.minecraft.world.level.material.Fluids.LAVA.getFluidType());

        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var encoded = ContainerFluidData.CODEC.encodeStart(ops, data).getOrThrow();
        ContainerFluidData decoded = ContainerFluidData.CODEC.parse(ops, encoded).getOrThrow();

        assertNotNull(decoded);
        assertEquals(data.getGeneratedSources().keySet(), decoded.getGeneratedSources().keySet(),
            "槽位集合必须一致");
        assertEquals(data.getGeneratedSources().get(3), decoded.getGeneratedSources().get(3),
            "流体类型必须一致（按注册 id 往返）");
    }

    @Test
    @DisplayName("相位快照 CODEC 往返：锁相状态能在「BE 附件 → 物品组件 → BE 附件」中存活")
    void phaseSnapshot_codec_roundTrip_preservesLockState() {
        // 边缘跟踪器的锁相三元组 (P, φ, d) + 幅度 —— 这正是跨形态要保住的东西
        var edge = new com.qiqi.li.living.domain.power.PhaseSnapshot.EdgeEntry(
            42L, 8, 3, 5, 2);
        var snap = new com.qiqi.li.living.domain.power.PhaseSnapshot(1000L, List.of(edge));

        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var encoded = com.qiqi.li.living.domain.power.PhaseSnapshot.CODEC
            .encodeStart(ops, snap).getOrThrow();
        var decoded = com.qiqi.li.living.domain.power.PhaseSnapshot.CODEC
            .parse(ops, encoded).getOrThrow();

        assertEquals(snap, decoded,
            "锁相状态（周期 / 周期内偏移 / 距上次上升沿 / 幅度）必须逐字段存活 —— "
                + "丢了它，多路振荡器重载后相对相位被洗牌，玩家调好的满相布局失效");
    }

    @Test
    @DisplayName("EMPTY 快照不进物品（与流体同款：稳态零成本，不产无意义条目）")
    void emptyPhaseSnapshot_isNotCarried() {
        // 稳态无振荡器时 capture 产出 EMPTY ⇒ 不值得搬。
        // 这是「有值才搬」约定的前提：EMPTY.equals 比较而非 ==
        assertTrue(com.qiqi.li.living.domain.power.PhaseSnapshot.EMPTY
            .equals(new com.qiqi.li.living.domain.power.PhaseSnapshot(0, List.of())),
            "EMPTY 判据是 equals —— 否则回填出来的等值快照会被当成无数据跳过");
    }

    @Test
    @DisplayName("snapshot 是独立副本：改原件不影响快照（否则物品组件会被后续 tick 污染）")
    void snapshot_isIndependent() {
        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(3, net.minecraft.world.level.material.Fluids.WATER.getFluidType());

        ContainerFluidData snap = data.snapshot();
        data.clearAll();

        assertEquals(0, data.getGeneratedSources().size(), "原件应已清空");
        assertEquals(1, snap.getGeneratedSources().size(), "快照必须保持原值 —— 它已经交给物品了");
    }

    @Test
    @DisplayName("clearAll 一次清干净（大箱两半共享同一对象，靠它避免第二次破坏再迁移 = 复制）")
    void clearAll_emptiesBothMaps() {
        ContainerFluidData data = new ContainerFluidData();
        data.registerGeneratedSource(1, net.minecraft.world.level.material.Fluids.WATER.getFluidType());

        assertFalse(data.isEmpty(), "前置：有源");
        data.clearAll();

        assertTrue(data.isEmpty(), "清空后必须 isEmpty —— 否则另一半破坏时会再迁移一次");
    }

    @Test
    @DisplayName("组件 CODEC 往返：空数据不产生条目（放置时不误建源）")
    void codec_roundTrip_emptyStaysEmpty() {
        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        var encoded = ContainerFluidData.CODEC.encodeStart(ops, new ContainerFluidData()).getOrThrow();
        ContainerFluidData decoded = ContainerFluidData.CODEC.parse(ops, encoded).getOrThrow();

        assertEquals(0, decoded.getGeneratedSources().size());
    }
}
