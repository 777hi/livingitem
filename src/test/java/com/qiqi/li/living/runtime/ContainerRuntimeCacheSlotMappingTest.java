package com.qiqi.li.living.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.furnace.FurnaceSegment;
import com.qiqi.li.living.domain.furnace.FurnaceSegment.FurnaceRuntime;
import com.qiqi.li.living.domain.furnace.TransformData;
import com.qiqi.li.testutil.FakeContainerContext;

/**
 * 运行时缓存的<b>读写槽位键对称性</b>（2026-10-27 审查 P1，熔炉/酿造台/漏斗域）。
 *
 * <p><b>被守的行为</b>：{@code update} 内部过 {@code toPhysicalSlot}（逻辑→物理），
 * {@code get(String, int)} 不过 —— 带 storageOffset 的容器（马/驴/骡 =1）读永远 miss
 * ⇒ 熔炉每 tick 重新点燃燃料（每秒 20 个）、酿造台每 tick 空耗烈焰粉，零产出且静默。
 * 修复：加 {@code get(ContainerContext, logicalSlot)} 对称重载，三个读点改走它。</p>
 */
class ContainerRuntimeCacheSlotMappingTest {

    /** 带 +1 存储偏移的替身 —— 马/驴/骡的槽位几何（物理 0 是鞍槽）。 */
    private static FakeContainerContext donkeyLike() {
        return new FakeContainerContext(16, 4) {
            @Override
            public int toPhysicalSlot(int logicalSlot) {
                return logicalSlot + 1;
            }
        };
    }

    private static RuntimeSegments segments(int burnTime) {
        return RuntimeSegments.EMPTY.with(FurnaceSegment.INSTANCE,
            new FurnaceRuntime(0, 200, burnTime, TransformData.EMPTY));
    }

    @AfterEach
    void tearDown() {
        ContainerRuntimeCache.clear();
    }

    @Test
    @DisplayName("带偏移上下文：update 写 logical=3 ⇒ get(ctx, 3) 必须读回（原来 miss ⇒ 熔炉每 tick 重燃）")
    void offsetContext_roundTripViaContextOverload() {
        FakeContainerContext ctx = donkeyLike();
        RuntimeSegments segs = segments(80);

        ContainerRuntimeCache.update(ctx, 3, segs);

        assertNotSame(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(ctx, 3),
            "读回自己上一 tick 写下的值 —— 不对称即熔炉每 tick 烧 1 个燃料、零产出");
        assertEquals(80, ContainerRuntimeCache.get(ctx, 3).get(FurnaceSegment.INSTANCE).burnTime());
    }

    @Test
    @DisplayName("存储键是物理槽：裸 get(key, 3) miss、get(key, 4) 命中（钉住内部键形，防两端再次错位）")
    void storedKeyIsPhysicalSlot() {
        FakeContainerContext ctx = donkeyLike();
        ContainerRuntimeCache.update(ctx, 3, segments(80));

        assertEquals(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(ctx.getContainerKey(), 3),
            "裸逻辑槽查物理键 = miss（正是原 bug 的读侧）");
        assertNotSame(RuntimeSegments.EMPTY, ContainerRuntimeCache.get(ctx.getContainerKey(), 4),
            "物理槽 = logical + 1");
    }

    @Test
    @DisplayName("恒等映射（方块/玩家容器）⇒ 两版 get 等价（回归：修复不改变既有行为）")
    void identityMapping_bothOverloadsAgree() {
        FakeContainerContext ctx = new FakeContainerContext(9, 3);
        ContainerRuntimeCache.update(ctx, 2, segments(50));

        assertEquals(ContainerRuntimeCache.get(ctx.getContainerKey(), 2),
            ContainerRuntimeCache.get(ctx, 2));
    }
}
