package com.qiqi.li.living.domain.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BeaconEffects 纯逻辑测试（设计稿 §2.5）。
 *
 * <p>判据来源：用户 2026-10-15 定的 5 个验证案例（全部 4 级）+ 2026-10-16
 * 「II 升级仅 L4」+ 「参考原版」档位门控。</p>
 */
class BeaconEffectsTest {

    private static BeaconConfig config(OreType ore, boolean amplified) {
        return new BeaconConfig(List.of(new BeaconConfig.Entry(ore, amplified)));
    }

    private static BeaconConfig config(BeaconConfig.Entry... entries) {
        return new BeaconConfig(List.of(entries));
    }

    @Test
    @DisplayName("buff 时长 = (9+等级×2)×20 刻（照原版 applyEffects）")
    void buffDuration() {
        assertEquals(180, BeaconEffects.buffDurationTicks(0));
        assertEquals(220, BeaconEffects.buffDurationTicks(1));
        assertEquals(260, BeaconEffects.buffDurationTicks(2));
        assertEquals(300, BeaconEffects.buffDurationTicks(3));
        assertEquals(340, BeaconEffects.buffDurationTicks(4));
    }

    @Nested
    @DisplayName("喂锭（门控 + 加槽/升级）")
    class Feed {

        @Test
        @DisplayName("L1 喂铁 ⇒ 追加 I 级槽")
        void addNewAtL1() {
            var updated = BeaconEffects.feed(BeaconConfig.EMPTY, OreType.IRON, 1);
            assertEquals(1, updated.size());
            assertEquals(OreType.IRON, updated.entries().get(0).ore());
            assertFalse(updated.entries().get(0).amplified());
        }

        @Test
        @DisplayName("L1 喂钻石（档位 3）⇒ 拒绝（不消耗）")
        void tierGateRejects() {
            assertNull(BeaconEffects.feed(BeaconConfig.EMPTY, OreType.DIAMOND, 1));
            assertNull(BeaconEffects.feed(BeaconConfig.EMPTY, OreType.NETHERITE, 1));
        }

        @Test
        @DisplayName("L3 喂钻石 ⇒ 通过")
        void tierGatePasses() {
            assertTrue(BeaconEffects.feed(BeaconConfig.EMPTY, OreType.DIAMOND, 3) != null);
        }

        @Test
        @DisplayName("未激活（0 级）⇒ 全部拒绝")
        void inactiveRejectsAll() {
            assertNull(BeaconEffects.feed(BeaconConfig.EMPTY, OreType.IRON, 0));
        }

        @Test
        @DisplayName("已在配置 + L1~3 ⇒ 拒绝（II 升级仅 L4）")
        void amplifyBelowL4Rejected() {
            var cfg = config(OreType.IRON, false);
            assertNull(BeaconEffects.feed(cfg, OreType.IRON, 1));
            assertNull(BeaconEffects.feed(cfg, OreType.IRON, 3));
        }

        @Test
        @DisplayName("已在配置 + L4 ⇒ 升级 II（同槽，不新增）")
        void amplifyAtL4() {
            var updated = BeaconEffects.feed(config(OreType.IRON, false), OreType.IRON, 4);
            assertEquals(1, updated.size());
            assertTrue(updated.isAmplified(OreType.IRON));
        }

        @Test
        @DisplayName("已是 II ⇒ 拒绝（无变化）")
        void alreadyAmplifiedRejected() {
            assertNull(BeaconEffects.feed(config(OreType.IRON, true), OreType.IRON, 4));
        }

        @Test
        @DisplayName("5 槽满 + 喂已在配置的矿（L4）⇒ 升级 II（同槽，不新增）")
        void fullAmplifyAtL4() {
            var full = config(
                new BeaconConfig.Entry(OreType.IRON, false),
                new BeaconConfig.Entry(OreType.GOLD, false),
                new BeaconConfig.Entry(OreType.NETHERITE, false),
                new BeaconConfig.Entry(OreType.EMERALD, false),
                new BeaconConfig.Entry(OreType.DIAMOND, false));
            assertTrue(full.isFull());
            var updated = BeaconEffects.feed(full, OreType.GOLD, 4);
            assertEquals(5, updated.size());
            assertTrue(updated.isAmplified(OreType.GOLD));
        }

        @Test
        @DisplayName("5 槽满 + 喂已在配置的矿（L3）⇒ 拒绝（II 升级仅 L4）")
        void fullAmplifyBelowL4Rejected() {
            var full = config(
                new BeaconConfig.Entry(OreType.IRON, false),
                new BeaconConfig.Entry(OreType.GOLD, false),
                new BeaconConfig.Entry(OreType.NETHERITE, false),
                new BeaconConfig.Entry(OreType.EMERALD, false),
                new BeaconConfig.Entry(OreType.DIAMOND, false));
            assertNull(BeaconEffects.feed(full, OreType.GOLD, 3));
        }
        // 注：5 种矿石恰好 5 槽 ⇒ 「满槽 + 新矿石」不可达（isFull 检查为防御性保留）

        @Test
        @DisplayName("null 矿石 ⇒ 拒绝")
        void nullOreRejected() {
            assertNull(BeaconEffects.feed(BeaconConfig.EMPTY, null, 4));
        }
    }

    @Nested
    @DisplayName("生效效果（min(堆叠, 档内槽数) 取前 N）")
    class ActiveEntries {

        @Test
        @DisplayName("案例 1：堆叠 1 + 钻石 I ⇒ [力量 I]")
        void case1() {
            var active = BeaconEffects.activeEntries(4, 1, config(OreType.DIAMOND, false));
            assertEquals(1, active.size());
            assertEquals(OreType.DIAMOND, active.get(0).ore());
        }

        @Test
        @DisplayName("案例 5：堆叠 2 + 绿宝石 I + 钻石 I ⇒ 2 个全生效")
        void case5() {
            var active = BeaconEffects.activeEntries(4, 2,
                config(new BeaconConfig.Entry(OreType.EMERALD, false),
                    new BeaconConfig.Entry(OreType.DIAMOND, false)));
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("档位休眠：L1 + 铁+钻石配置 ⇒ 只生效铁")
        void tierDormant() {
            var cfg = config(new BeaconConfig.Entry(OreType.IRON, false),
                new BeaconConfig.Entry(OreType.DIAMOND, false));
            var active = BeaconEffects.activeEntries(1, 5, cfg);
            assertEquals(1, active.size());
            assertEquals(OreType.IRON, active.get(0).ore());
        }

        @Test
        @DisplayName("堆叠数封顶：堆叠 1 + 3 档内效果 ⇒ 只生效第 1 个")
        void stackCaps() {
            var cfg = config(new BeaconConfig.Entry(OreType.IRON, false),
                new BeaconConfig.Entry(OreType.GOLD, false),
                new BeaconConfig.Entry(OreType.NETHERITE, false));
            var active = BeaconEffects.activeEntries(4, 1, cfg);
            assertEquals(1, active.size());
            assertEquals(OreType.IRON, active.get(0).ore());
        }

        @Test
        @DisplayName("空配置 ⇒ 空生效")
        void emptyConfig() {
            assertTrue(BeaconEffects.activeEntries(4, 3, BeaconConfig.EMPTY).isEmpty());
        }
    }

    @Nested
    @DisplayName("再生三档（5 案例逐字验证）")
    class RegenTier {

        @Test
        @DisplayName("案例 1：堆叠 1 + 力量 I、0 空槽 ⇒ I")
        void case1() {
            var active = BeaconEffects.activeEntries(4, 1, config(OreType.DIAMOND, false));
            assertEquals(BeaconSegment.RegenTier.I, BeaconEffects.regenTier(4, 1, active));
        }

        @Test
        @DisplayName("案例 2：堆叠 1 + 力量 II、0 空槽 ⇒ 无")
        void case2() {
            var active = BeaconEffects.activeEntries(4, 1, config(OreType.DIAMOND, true));
            assertEquals(BeaconSegment.RegenTier.NONE, BeaconEffects.regenTier(4, 1, active));
        }

        @Test
        @DisplayName("案例 3：堆叠 2 + 跳跃 I、1 空槽 ⇒ II")
        void case3() {
            var active = BeaconEffects.activeEntries(4, 2, config(OreType.EMERALD, false));
            assertEquals(BeaconSegment.RegenTier.II, BeaconEffects.regenTier(4, 2, active));
        }

        @Test
        @DisplayName("案例 4：堆叠 2 + 跳跃 II、1 空槽 ⇒ II（有空槽，加倍不消耗）")
        void case4() {
            var active = BeaconEffects.activeEntries(4, 2, config(OreType.EMERALD, true));
            assertEquals(BeaconSegment.RegenTier.II, BeaconEffects.regenTier(4, 2, active));
        }

        @Test
        @DisplayName("案例 5：堆叠 2 + 两个 I、0 空槽 ⇒ I")
        void case5() {
            var active = BeaconEffects.activeEntries(4, 2,
                config(new BeaconConfig.Entry(OreType.EMERALD, false),
                    new BeaconConfig.Entry(OreType.DIAMOND, false)));
            assertEquals(BeaconSegment.RegenTier.I, BeaconEffects.regenTier(4, 2, active));
        }

        @Test
        @DisplayName("非满级 ⇒ 无再生")
        void belowL4() {
            var active = BeaconEffects.activeEntries(3, 1, config(OreType.DIAMOND, false));
            assertEquals(BeaconSegment.RegenTier.NONE, BeaconEffects.regenTier(3, 1, active));
        }
    }
}
