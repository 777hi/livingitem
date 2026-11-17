package com.qiqi.li.living.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.core.CommentedConfig;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 宿主位置开关的默认值与加载/热重载接线守卫。
 *
 * <p>静态缓存只在 Loading / Reloading 事件里刷新；漏注册任何一条都会表现为
 * 「配置文件值正确，但 tick 读路径一直用旧值」。测试使用真实事件总线和内存 NightConfig，
 * 不反射调用私有 refresh。</p>
 */
class GameplayConfigTest {

    private IEventBus bus;
    private ModConfig ownConfig;

    @BeforeEach
    void setUp() {
        bus = BusBuilder.builder().build();
        GameplayConfig.attach(bus);
        bus.start();
        ownConfig = configFor(GameplayConfig.SPEC);
        load(new Values(false, false, false, false, false));
    }

    @AfterEach
    void restoreStaticState() {
        // 静态缓存跨测试共享：每例恢复默认全关，再卸载 spec，避免后续测试误读前例状态。
        install(new Values(false, false, false, false, false));
        bus.post(new ModConfigEvent.Reloading(ownConfig));
        GameplayConfig.SPEC.acceptConfig(null);
    }

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
    @DisplayName("宿主位置开关基准状态全关")
    void allHostAnchorSwitchesDefaultOff() {
        assertAll(false, false, false, false, false);
    }

    @Test
    @DisplayName("Loading：attach 注册的加载监听把自己的 spec 写入全部静态缓存")
    void loading_refreshesAllCachedValues() {
        load(new Values(true, false, true, false, true));

        assertAll(true, false, true, false, true);
    }

    @Test
    @DisplayName("Reloading：热重载清除 ConfigValue 缓存并刷新 tick 读路径")
    void reloading_refreshesChangedValues() {
        load(new Values(true, false, true, false, true));
        assertAll(true, false, true, false, true);

        install(new Values(false, true, false, true, false));
        bus.post(new ModConfigEvent.Reloading(ownConfig));

        assertAll(false, true, false, true, false);
    }

    @Test
    @DisplayName("无关 SPEC 的加载/重载事件不污染 GameplayConfig 静态缓存")
    void unrelatedSpec_isIgnored() {
        load(new Values(true, false, true, false, true));

        // 只替换本 SPEC 的底层值、不发布本 SPEC 事件；静态缓存此刻仍应保持上一组。
        // 若生产 guard 被删，无关事件会误触发 refresh 并读到这组新值，用例即红。
        install(new Values(false, true, false, true, false));
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.define("unrelated", true);
        ModConfig other = configFor(builder.build());
        bus.post(new ModConfigEvent.Loading(other));
        bus.post(new ModConfigEvent.Reloading(other));

        assertAll(true, false, true, false, true);
    }

    private void load(Values values) {
        install(values);
        bus.post(new ModConfigEvent.Loading(ownConfig));
    }

    /** 模拟 FML 在发布配置事件前安装/替换 LoadedConfig（acceptConfig 同时清 ConfigValue 缓存）。 */
    private static void install(Values values) {
        CommentedConfig config = CommentedConfig.inMemory();
        GameplayConfig.SPEC.correct(config);   // 先补完整结构/注释，acceptConfig 不再走自动纠正+save
        config.set(List.of("host_anchor", "tnt"), values.tnt());
        config.set(List.of("host_anchor", "water_wheel"), values.waterWheel());
        config.set(List.of("host_anchor", "tool_and_weapon"), values.toolAndWeapon());
        config.set(List.of("host_anchor", "beacon"), values.beacon());
        config.set(List.of("host_anchor", "portal"), values.portal());
        GameplayConfig.SPEC.acceptConfig(loaded(config));
    }

    private static ModConfig configFor(IConfigSpec spec) {
        ModConfig config = mock(ModConfig.class);
        when(config.getSpec()).thenReturn(spec);
        return config;
    }

    private static void assertAll(boolean tnt, boolean wheel, boolean tools,
                                  boolean beacon, boolean portal) {
        assertBoolean(tnt, GameplayConfig.tntUsesHostAnchor(), "TNT");
        assertBoolean(wheel, GameplayConfig.waterWheelUsesHostAnchor(), "水车");
        assertBoolean(tools, GameplayConfig.toolAndWeaponUsesHostAnchor(), "工具/武器");
        assertBoolean(beacon, GameplayConfig.beaconUsesHostAnchor(), "信标");
        assertBoolean(portal, GameplayConfig.portalUsesHostAnchor(), "地狱门");
    }

    private static void assertBoolean(boolean expected, boolean actual, String label) {
        if (expected) assertTrue(actual, label + " 应开启");
        else assertFalse(actual, label + " 应关闭");
    }

    private record Values(boolean tnt, boolean waterWheel, boolean toolAndWeapon,
                          boolean beacon, boolean portal) {}

    /**
     * FML 的唯一 ILoadedConfig 实现是包级 sealed {@code LoadedConfig}；测试用反射构造真实实现。
     * path=null ⇒ acceptConfig 的自动纠正若触发也不落盘，modConfig 仅供 record 持有。
     */
    private static IConfigSpec.ILoadedConfig loaded(CommentedConfig config) {
        try {
            Class<?> type = Class.forName("net.neoforged.fml.config.LoadedConfig");
            var ctor = type.getDeclaredConstructor(CommentedConfig.class, java.nio.file.Path.class, ModConfig.class);
            ctor.setAccessible(true);
            return (IConfigSpec.ILoadedConfig) ctor.newInstance(config, null, configFor(GameplayConfig.SPEC));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造 FML LoadedConfig 测试夹具", e);
        }
    }
}
