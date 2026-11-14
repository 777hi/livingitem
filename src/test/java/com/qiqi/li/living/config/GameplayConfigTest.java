package com.qiqi.li.living.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 宿主位置开关的默认关闭约束。
 *
 * <p>未加载配置时，读路径保持默认值；门玩法尚未实现，配置预留不代表功能接入。</p>
 */
class GameplayConfigTest {

    @Test
    @DisplayName("spec 能被构建（配置骨架没坏）")
    void spec_isBuilt() {
        assertNotNull(GameplayConfig.SPEC, "SPEC 为 null ⇒ 主类注册时会 NPE，配置文件根本不会生成");
    }

    @Test
    @DisplayName("活地狱门宿主位置项已注册且默认关闭")
    void portalHostAnchorOptionIsRegisteredAndDefaultsOff() {
        ModConfigSpec.BooleanValue portal = GameplayConfig.SPEC.getValues()
            .getRaw(List.of("host_anchor", "portal"));
        assertNotNull(portal, "host_anchor.portal 未注册");
        assertFalse(portal.getDefault(), "活地狱门借位默认关闭");
    }

    @Test
    @DisplayName("宿主位置开关默认全关（未加载配置时的读路径值）")
    void allHostAnchorSwitchesDefaultOff() {
        assertFalse(GameplayConfig.tntUsesHostAnchor(), "活背包里的炸弹默认不炸");
        assertFalse(GameplayConfig.waterWheelUsesHostAnchor(), "活背包里的水车默认不发电");
        assertFalse(GameplayConfig.toolAndWeaponUsesHostAnchor(), "活背包里的工具默认不挖矿");
        assertFalse(GameplayConfig.beaconUsesHostAnchor(), "活背包里的信标默认不开光");
        assertFalse(GameplayConfig.portalUsesHostAnchor(), "活背包里的地狱门默认不借宿主位置");
    }
}
