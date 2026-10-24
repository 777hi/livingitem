package com.qiqi.li.living.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 玩法开关的**默认值**（2026-10-24）。
 *
 * <p>四个开关全部默认关闭 —— 这是玩法承诺，不是实现细节：</p>
 * <ul>
 *   <li>它们给的是**加强玩法**（背包里的炸弹 / 随身发电 / 自动挖矿 / 随身光环），
 *       不是修复 ⇒ 不能因为"实现了"就默认生效</li>
 *   <li>默认关 ⇒ 装了模组但没动配置的玩家，行为和之前**完全一致**</li>
 * </ul>
 *
 * <p>⚠️ 这里测的是**未加载配置时的静态字段**（读路径只看它，见 {@link GameplayConfig} 类注释）。
 * 不测 {@code ModConfigSpec} 本身 —— 那是 NeoForge 的职责，测它等于测框架。</p>
 */
class GameplayConfigTest {

    @Test
    @DisplayName("spec 能被构建（配置骨架没坏）")
    void spec_isBuilt() {
        assertNotNull(GameplayConfig.SPEC, "SPEC 为 null ⇒ 主类注册时会 NPE，配置文件根本不会生成");
    }

    @Test
    @DisplayName("四个开关默认全关（未加载配置时的读路径值）")
    void allHostAnchorSwitchesDefaultOff() {
        assertFalse(GameplayConfig.tntUsesHostAnchor(), "活背包里的炸弹默认不炸");
        assertFalse(GameplayConfig.waterWheelUsesHostAnchor(), "活背包里的水车默认不发电");
        assertFalse(GameplayConfig.toolAndWeaponUsesHostAnchor(), "活背包里的工具默认不挖矿");
        assertFalse(GameplayConfig.beaconUsesHostAnchor(), "活背包里的信标默认不开光");
    }
}
