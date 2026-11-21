package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.qiqi.li.living.domain.water.ContainerFluidData;

/**
 * 单容器只读体检（{@code /livingitem debug container}）回归 ——
 * 守「诊断不得改变被诊断的状态」与「报告覆盖管道各层状态」两条不变量。
 */
class ContainerDiagnoseTest {

    private static final BlockPos POS = new BlockPos(10, 64, 10);

    @BeforeEach
    void isolateCaches() {
        ContainerLivingItemHandler.clearAllCaches();
        ContainerChunkCache.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        ContainerLivingItemHandler.clearAllCaches();
        ContainerChunkCache.getInstance().clear();
    }

    private static Level overworld() {
        Level level = Mockito.mock(Level.class);
        Mockito.when(level.dimension()).thenReturn(Level.OVERWORLD);
        Mockito.when(level.isClientSide()).thenReturn(false);
        long[] tick = {0};
        Mockito.when(level.getGameTime()).thenAnswer(inv -> tick[0]++);
        return level;
    }

    private static SimpleContainerContext chestContext(Level level) {
        return new SimpleContainerContext(new ItemStackHandler(27), null,
            List.of(POS), List.of(), level);
    }

    @Test
    @DisplayName("冷上下文体检：报告「无条目 / 无盖章」，且不创建任何跨 tick 状态")
    void diagnose_coldContext_reportsMissing_andCreatesNothing() {
        Level level = overworld();
        SimpleContainerContext ctx = chestContext(level);

        String report = ContainerLivingItemHandler.diagnoseContainer(ctx);

        assertTrue(report.contains("key=minecraft:overworld|chest_10_64_10"),
            "报告必须给出缓存键：" + report);
        assertTrue(report.contains("聚合条目=无"), "未进管道的容器必须如实报无条目：" + report);
        assertTrue(report.contains("盖章=无记录"), "从未 tick 的容器必须如实报无盖章：" + report);
        assertTrue(report.contains("活物品槽位追踪=无"), "无追踪必须如实报：" + report);

        // 全程只读：诊断不得创建聚合条目 / 盖章 / 位置索引 / 修订计数 / 容器级数据
        assertEquals(0, ContainerLivingItemHandler.trackedContainerCount(), "诊断不得创建槽位追踪");
        assertEquals(0, ContainerLivingItemHandler.getContainerRevision(ctx), "诊断不得创建条目（修订恒 0）");
        assertNull(ContainerLivingItemHandler.peekContainerDataByPos(
            level, POS, ContainerFluidData.KEY), "诊断不得创建容器级数据");
        assertEquals(0, ContainerChunkCache.getInstance().pendingRescanCount(Level.OVERWORLD),
            "诊断不得登记待重扫");
    }

    @Test
    @DisplayName("管道内容器体检：报告条目 / 闩锁 / 盖章年龄 / 槽位追踪 / 位置反查指向本键，且不推进修订")
    void diagnose_pipelineContainer_reportsState_withoutMutation() {
        Level level = overworld();
        SimpleContainerContext ctx = chestContext(level);
        ContainerLivingItemHandler.processContext(ctx, level);
        // 槽位追踪接缝直灌（同 ContainerCacheSweepTest：单测环境无功能注册，grouped 收不到活物品）
        ContainerLivingItemHandler.trackLivingSlots(
            ctx.getCacheKey(), Map.of(3, Items.FURNACE), ctx);

        long revisionBefore = ContainerLivingItemHandler.getContainerRevision(ctx);
        String report = ContainerLivingItemHandler.diagnoseContainer(ctx);

        assertTrue(report.contains("聚合条目=有"), "进过管道的容器必须报有条目：" + report);
        assertTrue(report.contains("静息闩锁="), "必须报静息闩锁状态：" + report);
        assertTrue(report.contains("盖章距今="), "必须报盖章年龄：" + report);
        assertTrue(report.contains("活物品槽位追踪=[3=" + Items.FURNACE),
            "槽位追踪必须如实报出（槽位 → 物品）：" + report);
        assertTrue(report.contains("位置反查 " + POS.toShortString() + "=指向本键"),
            "位置索引必须指回本键（mixin 热路径依赖）：" + report);
        assertEquals(revisionBefore, ContainerLivingItemHandler.getContainerRevision(ctx),
            "诊断不得推进修订计数");
    }

    @Test
    @DisplayName("病灶签名：位置索引缺失时必须显式报「无索引」而非静默")
    void diagnose_reportsMissingPositionIndex() {
        Level level = overworld();
        SimpleContainerContext ctx = chestContext(level);
        ContainerLivingItemHandler.processContext(ctx, level);

        // 模拟索引被清（发现缓存同族回归的残留形态）：按位置显式作废
        ContainerLivingItemHandler.removeDataByPos(level, POS);
        String report = ContainerLivingItemHandler.diagnoseContainer(ctx);

        assertTrue(report.contains("位置反查 " + POS.toShortString() + "=无索引"),
            "索引缺失必须显式可见，这是发现缓存回归的直接签名：" + report);
    }

    @Test
    @DisplayName("容器级数据键枚举：store 里有流体数据时报告其键名")
    void diagnose_listsContainerDataKeys() {
        Level level = overworld();
        SimpleContainerContext ctx = chestContext(level);
        ContainerFluidData.getOrCreate(ctx).registerGeneratedSource(0, Fluids.WATER.getFluidType());

        String report = ContainerLivingItemHandler.diagnoseContainer(ctx);

        assertTrue(report.contains("容器级数据=[fluid]"), "store 里的键必须可枚举：" + report);
    }
}
