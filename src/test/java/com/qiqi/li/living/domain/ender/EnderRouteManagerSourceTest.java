package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.core.BlockPos;

/**
 * 路由条目的「源是否可寻址」判据（2026-10-28，审查发现的「背包里的货推不出去」）。
 *
 * <p>路由条目只有两种寻址方式：{@code sourcePos}（方块）与 {@code player_<uuid>}（玩家背包 / 末影箱）。
 * 物品形态容器（{@code cuuid_}）两者都没有 ⇒ 提取端必然失败，而 {@code poll} 会先消费条目
 * ⇒ 「注册—丢弃」循环。判据抽成 {@link EnderRouteManager#isRoutableSource} 以便直接回归。</p>
 */
class EnderRouteManagerSourceTest {

    private static ContainerContext ctxWith(BlockPos pos, String containerKey) {
        ContainerContext ctx = mock(ContainerContext.class);
        when(ctx.getBlockPos()).thenReturn(pos);
        when(ctx.getContainerKey()).thenReturn(containerKey);
        return ctx;
    }

    @Test
    @DisplayName("方块容器：永远可作源")
    void blockContainer_isRoutable() {
        assertTrue(EnderRouteManager.isRoutableSource(
            ctxWith(new BlockPos(1, 64, 1), "chest_1_64_1")));
    }

    @Test
    @DisplayName("玩家背包（player_ 键、无位置）：可作源（提取端按 UUID 反查）")
    void playerInventory_isRoutable() {
        assertTrue(EnderRouteManager.isRoutableSource(
            ctxWith(null, "player_" + UUID.randomUUID())));
    }

    @Test
    @DisplayName("物品形态容器（cuuid_ 键、无位置）：不可作源（提取端无法定位）")
    void itemFormContainer_isNotRoutable() {
        assertFalse(EnderRouteManager.isRoutableSource(
            ctxWith(null, "cuuid_" + UUID.randomUUID())));
    }

    @Test
    @DisplayName("未知键形放行：不为难未识别的旧形态（保守方向）")
    void unknownKey_isRoutable() {
        assertTrue(EnderRouteManager.isRoutableSource(ctxWith(null, "weird_key")));
    }
}
