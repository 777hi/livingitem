package com.qiqi.li.living.domain.water;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.level.material.Fluids;

/**
 * {@link FluidFlowClientCache} 的<b>按键路由</b>守卫（2026-10-06）。
 *
 * <p>为什么需要它：末影箱兼容层曾用 {@code player_<uuid>_ender_chest}，与背包键
 * {@code player_<uuid>} <b>同前缀</b> ⇒ 末影箱的水泄漏到玩家物品栏（10-05 实测）。
 * 该兼容层已整体撤除 ⇒ 键只剩两种形态（玩家背包 / 方块容器），前缀天然不冲突。
 * 本类把这条<b>不变量本体</b>钉住：将来若再引入第三种玩家作用域容器（键仍以
 * {@code player_} 开头），这里会立刻红。</p>
 */
class FluidFlowClientCacheTest {

    private static FluidFlowClientCache.FlowSnapshot snapshot(int width) {
        return new FluidFlowClientCache.FlowSnapshot(width, List.of(Fluids.WATER.getFluidType()),
            Map.of(0, new int[]{0, -1, 0}));
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        FluidFlowClientCache.clear();
    }

    @Test
    @DisplayName("背包键 player_<uuid> ⇒ 玩家背包桶（不落 BE 桶）")
    void playerKey_routesToPlayerBucket() {
        FluidFlowClientCache.update("player_abc-123", snapshot(9));

        assertTrue(!FluidFlowClientCache.getPlayer().isEmpty(), "背包快照应进背包桶");
        assertTrue(FluidFlowClientCache.get().isEmpty(), "BE 桶必须为空（否则会画到别人的容器里）");
    }

    @Test
    @DisplayName("方块容器键 chest… ⇒ BE 桶（不落背包桶）")
    void blockKey_routesToContainerBucket() {
        FluidFlowClientCache.update("chest|minecraft:overworld|12,64,-8", snapshot(9));

        assertTrue(!FluidFlowClientCache.get().isEmpty(), "方块容器快照应进 BE 桶");
        assertTrue(FluidFlowClientCache.getPlayer().isEmpty(), "背包桶必须为空（防泄漏到物品栏）");
    }

    @Test
    @DisplayName("clear()（界面关闭）⇒ 两桶归零，不闪现旧水")
    void clear_resetsBothBuckets() {
        FluidFlowClientCache.update("player_abc", snapshot(9));
        FluidFlowClientCache.update("chest|x|1,2,3", snapshot(9));

        FluidFlowClientCache.clear();

        assertTrue(FluidFlowClientCache.getPlayer().isEmpty() && FluidFlowClientCache.get().isEmpty(),
            "关闭界面后两份缓存都应清空");
    }
}
