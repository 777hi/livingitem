package com.qiqi.li.living.config;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 玩法开关（{@code SERVER} 型配置，2026-10-24）。
 *
 * <h3>为什么是 SERVER 型</h3>
 * <p>这些开关影响的是**服务端行为**（爆炸 / 应力 / 挖矿 / 光环），客户端必须知道服务端的选择 ——
 * {@code SERVER} 型会**自动同步**给客户端，且可按存档覆盖（{@code world/serverconfig/}）。
 * {@code COMMON} 不同步，联机时客户端不知道服务端开没开。</p>
 *
 * <h3>为什么要把值缓存到静态字段</h3>
 * <p>{@link ModConfigSpec.ConfigValue#get()} 在配置**尚未加载**时会抛异常。
 * 而这些值在 tick 路径上被读（每容器每 tick）⇒ 直接 {@code get()} 既慢又会在
 * "配置还没加载"的窗口里炸。所以：加载 / 重载时写进静态字段，读路径只读字段。
 * 未加载时字段是默认值（{@code false}）—— 与"配置不存在"的语义一致。</p>
 *
 * <h3>这些开关控制什么</h3>
 * <p>物品形态容器（活背包）**没有自己的世界位置**，它借宿主的位置（`ContainerContext#getHostAnchor`）。
 * 借来干什么，是玩法：背包里的炸弹炸在宿主处、水车给宿主发电、工具用宿主的眼睛挖矿、
 * 信标给宿主开光环。全部**默认关闭** —— 关掉时那些功能在活背包里不工作（活 TNT 引信走完即熄灭）。</p>
 */
public final class GameplayConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue TNT;
    private static final ModConfigSpec.BooleanValue WATER_WHEEL;
    private static final ModConfigSpec.BooleanValue TOOL_AND_WEAPON;
    private static final ModConfigSpec.BooleanValue BEACON;

    /** 读路径只看这些字段（见类注释：未加载时保持默认 false）。 */
    private static volatile boolean tnt;
    private static volatile boolean waterWheel;
    private static volatile boolean toolAndWeapon;
    private static volatile boolean beacon;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "【玩法开关】活背包里的活物品能不能借用宿主的位置。",
                "宿主 = 活背包所在的地方（玩家身上 / 箱子 / 实体容器）。",
                "默认全关，开启属于加强玩法。")
            .push("host_anchor");

        TNT = builder.comment(
                "活背包里的活 TNT：开启后炸在宿主处；关闭时引信走完熄灭，挪到别处也不会自爆。")
            .define("tnt", false);

        WATER_WHEEL = builder.comment(
                "活背包里的活水车：开启后向宿主位置输出应力。")
            .define("water_wheel", false);

        TOOL_AND_WEAPON = builder.comment(
                "活背包里的活工具 / 活武器：开启后用宿主位置干活。")
            .define("tool_and_weapon", false);

        BEACON = builder.comment(
                "活背包里的活信标：开启后用宿主位置开光。")
            .define("beacon", false);

        builder.pop();
        SPEC = builder.build();
    }

    private GameplayConfig() {}

    /**
     * 注册加载 / 重载监听。
     *
     * <p>⚠️ 必须注册：静态字段只在事件里刷新，漏了会一直停在默认值（{@code false}），
     * 表现是「配置改了没生效」。</p>
     */
    public static void attach(IEventBus modBus) {
        modBus.addListener(GameplayConfig::onLoading);
        modBus.addListener(GameplayConfig::onReloading);
    }

    private static void onLoading(ModConfigEvent.Loading event) {
        refresh(event);
    }

    private static void onReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    /** 只处理自己的 spec（{@code ModConfigEvent} 是给**所有**配置发的）。 */
    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        tnt = TNT.get();
        waterWheel = WATER_WHEEL.get();
        toolAndWeapon = TOOL_AND_WEAPON.get();
        beacon = BEACON.get();
    }

    // ── 读路径（tick 里调用，只读静态字段）──

    public static boolean tntUsesHostAnchor() {
        return tnt;
    }

    public static boolean waterWheelUsesHostAnchor() {
        return waterWheel;
    }

    public static boolean toolAndWeaponUsesHostAnchor() {
        return toolAndWeapon;
    }

    public static boolean beaconUsesHostAnchor() {
        return beacon;
    }
}
