package com.qiqi.li.living.domain.ender;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ItemFormLiveViews;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 路由条目的「源是否可寻址」判据（2026-10-28）。
 *
 * <p>寻址三种：方块位置 / 玩家容器（`player_`）/ 物品形态（`cuuid_`，靠
 * {@link ItemFormLiveViews} 取本 tick 的活视图）。物品形态要求**注册时活视图存在** ——
 * 注册发生在源容器自己的 tick 里，正常一定命中；命中不了说明背包没在 tick。</p>
 */
class EnderRouteManagerSourceTest {

    @AfterEach
    void tearDown() {
        ItemFormLiveViews.clear();
    }

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
    @DisplayName("物品形态容器：活视图已登记 ⇒ 可作源（提取端按容器 UUID 取视图）")
    void itemFormContainer_withLiveView_isRoutable() {
        UUID uuid = UUID.randomUUID();
        ItemFormLiveViews.register(uuid, new ItemStackHandler(9), null);

        assertTrue(EnderRouteManager.isRoutableSource(ctxWith(null, "cuuid_" + uuid)));
    }

    @Test
    @DisplayName("物品形态容器：活视图未登记 ⇒ 不可作源（注册必丢，别制造循环）")
    void itemFormContainer_withoutLiveView_isNotRoutable() {
        assertFalse(EnderRouteManager.isRoutableSource(
            ctxWith(null, "cuuid_" + UUID.randomUUID())));
    }

    @Test
    @DisplayName("未知键形放行：不为难未识别的旧形态（保守方向）")
    void unknownKey_isRoutable() {
        assertTrue(EnderRouteManager.isRoutableSource(ctxWith(null, "weird_key")));
    }
}
