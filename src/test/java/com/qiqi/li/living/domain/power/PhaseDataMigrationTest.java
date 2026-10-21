package com.qiqi.li.living.domain.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * {@link PhaseDataMigration} 的守卫（2026-10-21 补 —— 此前该文件零测试，而它的搬运逻辑
 * 比流体更复杂：快照要 {@link PhaseSnapshot#restoreInto} 还原进账本，且 {@code clock}
 * 必须与 writeback 同源）。
 *
 * <p>守两件事：</p>
 * <ol>
 *   <li><b>moveOut</b> 只对 {@code contentRetaining} 声明的容器搬（与破坏路径同一判据）；</li>
 *   <li><b>moveIn</b> 在「没有 ctx」的降级路径上也必须把快照写进附件 —— 真机上
 *       {@code resolveContextAt} 可能因能力面未就绪返回 null，那时只写附件 + WARN，
 *       至少存档级还在。</li>
 * </ol>
 */
class PhaseDataMigrationTest {

    private static PhaseSnapshot snapshot() {
        var edge = new PhaseSnapshot.EdgeEntry(42L, 8, 3, 5, 2);
        return new PhaseSnapshot(1000L, List.of(edge));
    }

    private static BlockEntity beWith(BlockEntityType<?> type, PhaseSnapshot snapshot) {
        BlockEntity be = mock(BlockEntity.class);
        doReturn(type).when(be).getType();
        when(be.getData(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value())).thenReturn(snapshot);
        return be;
    }

    @Test
    @DisplayName("moveOut：contentRetaining 容器 ⇒ 快照交给物品")
    void moveOut_retainingContainer_writesComponent() {
        BlockEntity be = beWith(BlockEntityType.SHULKER_BOX, snapshot());
        ItemStack target = mock(ItemStack.class);
        when(target.isEmpty()).thenReturn(false);

        assertTrue(PhaseDataMigration.moveOut(be, target), "潜影盒声明了 contentRetaining ⇒ 应搬");

        var captor = org.mockito.ArgumentCaptor.forClass(PhaseSnapshot.class);
        verify(target).set(Mockito.eq(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get()), captor.capture());
        assertNotNull(captor.getValue(), "写进物品的不能是 null");
        assertFalse(PhaseSnapshot.EMPTY.equals(captor.getValue()), "不能把 EMPTY 当数据搬过去");
        assertEquals(1, captor.getValue().edges().size(), "锁相边必须一条不少");
    }

    @Test
    @DisplayName("moveOut：非 contentRetaining 容器 ⇒ 不搬（与破坏路径同一判据）")
    void moveOut_nonRetainingContainer_doesNotMove() {
        BlockEntity be = beWith(BlockEntityType.CHEST, snapshot());
        ItemStack target = mock(ItemStack.class);
        when(target.isEmpty()).thenReturn(false);

        assertFalse(PhaseDataMigration.moveOut(be, target), "原版箱子内容随方块消失 ⇒ 相位也不搬");
        verify(target, Mockito.never())
            .set(Mockito.eq(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get()), Mockito.any());
    }

    @Test
    @DisplayName("moveOut：稳态 EMPTY 快照 ⇒ 不搬（零成本，不产无意义条目）")
    void moveOut_emptySnapshot_isNotCarried() {
        BlockEntity be = beWith(BlockEntityType.SHULKER_BOX, PhaseSnapshot.EMPTY);
        ItemStack target = mock(ItemStack.class);
        when(target.isEmpty()).thenReturn(false);

        assertFalse(PhaseDataMigration.moveOut(be, target));
        verify(target, Mockito.never())
            .set(Mockito.eq(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get()), Mockito.any());
    }

    @Test
    @DisplayName("moveIn：物品上没有相位组件 ⇒ 安全返回，不抛")
    void moveIn_noComponent_isNoop() {
        BlockEntity be = beWith(BlockEntityType.SHULKER_BOX, PhaseSnapshot.EMPTY);
        ItemStack source = mock(ItemStack.class);
        when(source.isEmpty()).thenReturn(false);
        when(source.get(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get())).thenReturn(null);

        PhaseDataMigration.moveIn(be, source);
        verify(be, Mockito.never())
            .setData(Mockito.eq(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value()), Mockito.any());
    }

    @Test
    @DisplayName("moveIn：有组件但拿不到 ctx ⇒ 降级只写附件（真机能力面未就绪的路径）")
    void moveIn_withoutContext_writesAttachmentOnly() {
        BlockEntity be = beWith(BlockEntityType.SHULKER_BOX, PhaseSnapshot.EMPTY);
        when(be.getLevel()).thenReturn(null);   // 无 Level ⇒ resolveContextOf 返回 null

        ItemStack source = mock(ItemStack.class);
        when(source.isEmpty()).thenReturn(false);
        when(source.get(PowerComponents.CONTAINER_PHASE_SNAPSHOT_ITEM.get())).thenReturn(snapshot());

        PhaseDataMigration.moveIn(be, source);

        var captor = org.mockito.ArgumentCaptor.forClass(PhaseSnapshot.class);
        verify(be).setData(Mockito.eq(PowerComponents.CONTAINER_PHASE_SNAPSHOT.value()), captor.capture());
        assertEquals(1, captor.getValue().edges().size(), "降级路径也要把快照写进附件（存档级还在）");
    }
}
