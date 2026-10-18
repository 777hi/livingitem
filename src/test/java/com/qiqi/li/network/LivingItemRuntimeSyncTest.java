package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qiqi.li.living.container.ContainerKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 末影箱 viewer 门控判定测试（2026-10-18）。
 *
 * <p>守卫的回归：末影箱包曾无条件每 tick 直发主人（无 viewer 匹配）⇒ 冲掉客户端通用缓存里
 * 「当前打开的世界容器」⇒ 容器 GUI 的 tooltip 遥测全丢。同族 bug 在流体轨连修三次
 * （{@code FluidFlowServerSync}「第 ③ 次泄漏修复」），本次是同一病因换轨重现。
 * 判据本身极简，测试的价值在于把规则钉住：将来谁把门控删了 / 把背包也门控了，这里立刻红。</p>
 */
class LivingItemRuntimeSyncTest {

    @Test
    @DisplayName("背包（PLAYER_INV）不门控——写独立背包缓存，不冲任何东西")
    void playerInv_neverGated() {
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.PLAYER_INV, false));
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.PLAYER_INV, true));
    }

    @Test
    @DisplayName("末影箱：只有真开着 GUI 才发（门控是根治点）")
    void enderChest_gatedByViewing() {
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.ENDER_CHEST, true));
        assertFalse(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.ENDER_CHEST, false));
    }

    @Test
    @DisplayName("世界容器 / 掉落物不走本路径（viewer 匹配另走），恒 true 仅防御")
    void nonOwnedKinds_defensive() {
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.WORLD_BLOCK, false));
        assertTrue(LivingItemRuntimeSync.shouldSendToOwner(ContainerKind.ITEM_ENTITY, false));
    }
}
