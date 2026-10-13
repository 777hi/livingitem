package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * 容器键解析唯一入口的守卫（2026-10-13）。
 *
 * <p>背景：2026-10-13 一次性暴露 3 处同源 bug，全部因为消费方各自从键形反推 ——
 * ① 背包守卫误判「没开界面」；② 末影箱 UUID 解析漏剥 {@code _ender_chest} 后缀（抛异常被吞
 * ⇒ 从不发包）；③ 客户端按屏幕类型推断种类（与服务端键前缀判断相反 ⇒ 读写错位）。</p>
 *
 * <p>本测试钉住「5 种键形各自解析正确」+「末影箱与背包必须区分开」（②的回归）
 * +「未知形态不抛异常、返回 null」。</p>
 */
class ContainerKeysTest {

    private static final UUID UUID_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PLAYER = "player_" + UUID_A;
    private static final String ENDER = "player_" + UUID_A + "_ender_chest";

    // ============================================================
    // kindOf —— 5 种键形
    // ============================================================

    @Test
    @DisplayName("kindOf：5 种键形各自归位（背包 / 末影箱必须分开 —— 它们前缀相同）")
    void kindOf_allForms() {
        assertEquals(ContainerKind.PLAYER_INV, ContainerKeys.kindOf(PLAYER));
        assertEquals(ContainerKind.ENDER_CHEST, ContainerKeys.kindOf(ENDER),
            "⚠️ 末影箱与背包前缀相同（player_）⇒ 必须按后缀区分，这正是 2026-10-13 那次的形态");
        assertEquals(ContainerKind.WORLD_BLOCK, ContainerKeys.kindOf("chest_1_2_3"));
        assertEquals(ContainerKind.WORLD_BLOCK, ContainerKeys.kindOf("minecraft:overworld|chest_1_2_3"),
            "缓存键带 <维度>| 前缀 ⇒ 剥前缀后仍判世界容器");
        assertEquals(ContainerKind.WORLD_BLOCK, ContainerKeys.kindOf("container_minecraft:overworld_1_2_3"),
            "世界实体容器（container_<dim>_x_y_z）");
        assertEquals(ContainerKind.ITEM_ENTITY, ContainerKeys.kindOf("item_entity_" + UUID_A));
    }

    @Test
    @DisplayName("kindOf：未知 / 空 / null 返回 null（不抛异常，由调用方决定拒绝策略）")
    void kindOf_unknown() {
        assertNull(ContainerKeys.kindOf(null));
        assertNull(ContainerKeys.kindOf(""));
        assertNull(ContainerKeys.kindOf("garbage"));
        assertNull(ContainerKeys.kindOf("player"));   // 缺下划线
    }

    // ============================================================
    // ownerOf —— bug ② 的回归
    // ============================================================

    @Test
    @DisplayName("ownerOf：背包与末影箱都能解析出 UUID（末影箱那条曾抛异常被吞 ⇒ 从不发包）")
    void ownerOf_bothPlayerForms() {
        assertEquals(UUID_A, ContainerKeys.ownerOf(PLAYER));
        assertEquals(UUID_A, ContainerKeys.ownerOf(ENDER),
            "🔴 回归：旧实现 UUID.fromString(\"<uuid>_ender_chest\") 抛 IllegalArgumentException 被吞");
    }

    @Test
    @DisplayName("ownerOf：非玩家容器 / 形态异常返回 null（不抛异常）")
    void ownerOf_nonPlayer() {
        assertNull(ContainerKeys.ownerOf("chest_1_2_3"));
        assertNull(ContainerKeys.ownerOf("item_entity_" + UUID_A));
        assertNull(ContainerKeys.ownerOf("player_not-a-uuid"), "非法 UUID ⇒ null 而非抛异常");
        assertNull(ContainerKeys.ownerOf(null));
    }

    // ============================================================
    // isOwnedByPlayer —— 替代散落的 startsWith("player_")
    // ============================================================

    @Test
    @DisplayName("isOwnedByPlayer：背包 / 末影箱为真；世界容器 / 掉落物 / null 为假")
    void isOwnedByPlayer_forms() {
        assertTrue(ContainerKeys.isOwnedByPlayer(PLAYER));
        assertTrue(ContainerKeys.isOwnedByPlayer(ENDER));
        assertFalse(ContainerKeys.isOwnedByPlayer("chest_1_2_3"));
        assertFalse(ContainerKeys.isOwnedByPlayer("item_entity_" + UUID_A));
        assertFalse(ContainerKeys.isOwnedByPlayer(null), "null 必须安全（旧写法 key.startsWith 会 NPE）");
    }

    // ============================================================
    // positionsOf
    // ============================================================

    @Test
    @DisplayName("positionsOf：单箱一段、大箱两段、缓存键剥前缀、container_ 取末三段（维度可含下划线）")
    void positionsOf_forms() {
        assertEquals(List.of(new BlockPos(1, 2, 3)), ContainerKeys.positionsOf("chest_1_2_3"));
        assertEquals(List.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6)),
            ContainerKeys.positionsOf("chest_1_2_3_4_5_6"), "大箱子两段坐标都要");
        assertEquals(List.of(new BlockPos(1, 2, 3)),
            ContainerKeys.positionsOf("minecraft:overworld|chest_1_2_3"), "缓存键先剥维度前缀");
        assertEquals(List.of(new BlockPos(10, 20, 30)),
            ContainerKeys.positionsOf("container_minecraft:the_nether_10_20_30"),
            "⚠️ 维度 id 可含下划线（the_nether）⇒ 必须取末三段而非固定下标");
        assertEquals(List.of(), ContainerKeys.positionsOf(PLAYER), "玩家容器无坐标");
        assertEquals(List.of(), ContainerKeys.positionsOf(null));
    }

    // ============================================================
    // dimPrefixOf
    // ============================================================

    @Test
    @DisplayName("dimPrefixOf：缓存键取出维度；裸键返回 null")
    void dimPrefixOf_forms() {
        assertEquals("minecraft:overworld", ContainerKeys.dimPrefixOf("minecraft:overworld|chest_1_2_3"));
        assertNull(ContainerKeys.dimPrefixOf("chest_1_2_3"), "裸键无维度");
        assertNull(ContainerKeys.dimPrefixOf(PLAYER));
        assertNull(ContainerKeys.dimPrefixOf(null));
    }
}
