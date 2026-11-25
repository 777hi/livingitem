package com.qiqi.li.living.config;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 活工具 / 活武器<b>环动画</b>的本机视觉配置（CLIENT 型，2026-11-26）。
 *
 * <h3>为什么独立一份</h3>
 * <ul>
 *   <li><b>纯表现层</b>：不参与玩法判定、不需要服务器权威，改了只影响自己的画面 ⇒
 *       必须是 {@code CLIENT} 型（自动同步给本机、可在 Mod 菜单热重载），
 *       不能塞进 {@link GameplayConfig}（那是 SERVER 型，会同步给全服）。</li>
 *   <li><b>参数原来散在渲染器的 private constant 里</b>：想调一次就得改代码重编译，
 *       而且 {@code PULSE_TICKS} / {@code ATTACK_RING_TICKS} 的联动藏在几处调用点之间，
 *       拆开调极易踩坑（见 {@code LivingToolModelRenderer#pulseWindow}）。</li>
 * </ul>
 *
 * <h3>读取纪律</h3>
 * <p>{@link ModConfigSpec.ConfigValue#get()} 在配置未加载时会抛异常，而渲染器是每帧热路径
 * ⇒ 加载 / 重载事件里刷新进 volatile 字段，读路径只调 getter（与 {@link TlmClientConfig} 同款）。
 * 所有值都按 {@code defineInRange} 限幅，getter 额外做非有限值兜底，脏 toml 不会让动画崩掉。</p>
 */
public final class ToolVisualConfig {

    public static final String FILE_NAME = "living_item-tool-visual-client.toml";

    // ── 默认值（与改造前的渲染器常量逐位一致）────────────────────────────────
    public static final int DEFAULT_PULSE_TICKS = 6;
    public static final int DEFAULT_ATTACK_RING_TICKS = 6;
    public static final double DEFAULT_PULSE_SCALE = 0.30;
    public static final int DEFAULT_SPIN_PERIOD_MIN = 4;
    public static final int DEFAULT_SPIN_PERIOD_MAX = 20;
    public static final double DEFAULT_SPIN_PERIOD_DIVISOR = 10.0;
    public static final int DEFAULT_RETURN_TICKS = 8;
    public static final double DEFAULT_RING_RADIUS_MAX = 1.77;
    public static final double DEFAULT_RING_RADIUS_HALF_SATURATION = 21.0;

    // ── 范围 ────────────────────────────────────────────────────────────────
    public static final int MIN_PULSE_TICKS = 1;
    public static final int MAX_PULSE_TICKS = 60;
    public static final int MIN_ATTACK_RING_TICKS = 1;
    public static final int MAX_ATTACK_RING_TICKS = 60;
    public static final double MIN_PULSE_SCALE = 0.0;
    public static final double MAX_PULSE_SCALE = 2.0;
    /** 转圈周期下限：低于 4 在 60fps 下会走样成倒转 / 抖动（车轮效应），配置也不放开。 */
    public static final int MIN_SPIN_PERIOD = 4;
    public static final int MAX_SPIN_PERIOD = 60;
    public static final int MAX_SPIN_PERIOD_MAX = 120;
    public static final double MIN_SPIN_PERIOD_DIVISOR = 0.1;
    public static final double MAX_SPIN_PERIOD_DIVISOR = 1000.0;
    public static final int MIN_RETURN_TICKS = 1;
    public static final int MAX_RETURN_TICKS = 60;
    public static final double MIN_RING_RADIUS_MAX = 0.1;
    public static final double MAX_RING_RADIUS_MAX = 10.0;
    public static final double MIN_RING_RADIUS_HALF_SATURATION = 0.5;
    public static final double MAX_RING_RADIUS_HALF_SATURATION = 200.0;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue PULSE_TICKS;
    private static final ModConfigSpec.IntValue ATTACK_RING_TICKS;
    private static final ModConfigSpec.DoubleValue PULSE_SCALE;
    private static final ModConfigSpec.IntValue SPIN_PERIOD_MIN;
    private static final ModConfigSpec.IntValue SPIN_PERIOD_MAX;
    private static final ModConfigSpec.DoubleValue SPIN_PERIOD_DIVISOR;
    private static final ModConfigSpec.IntValue RETURN_TICKS;
    private static final ModConfigSpec.DoubleValue RING_RADIUS_MAX;
    private static final ModConfigSpec.DoubleValue RING_RADIUS_HALF_SATURATION;

    /** 未加载配置时用默认值；渲染热路径只读这些 volatile primitive。 */
    private static volatile int pulseTicks = DEFAULT_PULSE_TICKS;
    private static volatile int attackRingTicks = DEFAULT_ATTACK_RING_TICKS;
    private static volatile float pulseScale = (float) DEFAULT_PULSE_SCALE;
    private static volatile int spinPeriodMin = DEFAULT_SPIN_PERIOD_MIN;
    private static volatile int spinPeriodMax = DEFAULT_SPIN_PERIOD_MAX;
    private static volatile float spinPeriodDivisor = (float) DEFAULT_SPIN_PERIOD_DIVISOR;
    private static volatile int returnTicks = DEFAULT_RETURN_TICKS;
    private static volatile float ringRadiusMax = (float) DEFAULT_RING_RADIUS_MAX;
    private static volatile float ringRadiusHalfSaturation = (float) DEFAULT_RING_RADIUS_HALF_SATURATION;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment(
                "Client-only visuals for living tool / weapon rings.",
                "Changes hot-reload via the Mods menu; they never affect gameplay or other players.")
            .translation("living_item.configuration.tool_visual")
            .push("tool_visual");

        PULSE_TICKS = builder.comment(
                "Duration (ticks) of the scale pulse played on strike / interaction.",
                "Also the fallback attack-ring window; see attack_ring_ticks.")
            .translation("living_item.configuration.tool_visual.pulse_ticks")
            .defineInRange("pulse_ticks", DEFAULT_PULSE_TICKS, MIN_PULSE_TICKS, MAX_PULSE_TICKS);

        ATTACK_RING_TICKS = builder.comment(
                "How long (ticks) a striking weapon stays at the target before flying back.",
                "Default equals pulse_ticks. Raise it alone to linger longer without stretching the pulse.",
                "The pulse-state bookkeeping is pruned at max(pulse_ticks, attack_ring_ticks).")
            .translation("living_item.configuration.tool_visual.attack_ring_ticks")
            .defineInRange("attack_ring_ticks", DEFAULT_ATTACK_RING_TICKS,
                MIN_ATTACK_RING_TICKS, MAX_ATTACK_RING_TICKS);

        PULSE_SCALE = builder.comment(
                "Peak enlargement of the pulse (0.30 = 1.30x at mid-window).")
            .translation("living_item.configuration.tool_visual.pulse_scale")
            .defineInRange("pulse_scale", DEFAULT_PULSE_SCALE, MIN_PULSE_SCALE, MAX_PULSE_SCALE);

        SPIN_PERIOD_MIN = builder.comment(
                "Fastest digging-ring spin (ticks per turn). Hard floor 4: lower values alias at 60fps.")
            .translation("living_item.configuration.tool_visual.spin_period_min")
            .defineInRange("spin_period_min", DEFAULT_SPIN_PERIOD_MIN, MIN_SPIN_PERIOD, MAX_SPIN_PERIOD);

        SPIN_PERIOD_MAX = builder.comment(
                "Slowest digging-ring spin. If lower than spin_period_min, min wins.")
            .translation("living_item.configuration.tool_visual.spin_period_max")
            .defineInRange("spin_period_max", DEFAULT_SPIN_PERIOD_MAX, MIN_SPIN_PERIOD, MAX_SPIN_PERIOD_MAX);

        SPIN_PERIOD_DIVISOR = builder.comment(
                "Spin period = ceil(estimated dig ticks / this value), then clamped to [min, max].")
            .translation("living_item.configuration.tool_visual.spin_period_divisor")
            .defineInRange("spin_period_divisor", DEFAULT_SPIN_PERIOD_DIVISOR,
                MIN_SPIN_PERIOD_DIVISOR, MAX_SPIN_PERIOD_DIVISOR);

        RETURN_TICKS = builder.comment(
                "Ticks a tool takes to fly back to its idle position after finishing work.")
            .translation("living_item.configuration.tool_visual.return_ticks")
            .defineInRange("return_ticks", DEFAULT_RETURN_TICKS, MIN_RETURN_TICKS, MAX_RETURN_TICKS);

        RING_RADIUS_MAX = builder.comment(
                "Asymptotic radius cap (blocks) shared by back / digging / attack rings.",
                "Radius(n) = max * n / (n + half_saturation).")
            .translation("living_item.configuration.tool_visual.ring_radius_max")
            .defineInRange("ring_radius_max", DEFAULT_RING_RADIUS_MAX,
                MIN_RING_RADIUS_MAX, MAX_RING_RADIUS_MAX);

        RING_RADIUS_HALF_SATURATION = builder.comment(
                "Item count at which the ring reaches half of ring_radius_max.",
                "Larger = tighter start, longer spread as the count grows.")
            .translation("living_item.configuration.tool_visual.ring_radius_half_saturation")
            .defineInRange("ring_radius_half_saturation", DEFAULT_RING_RADIUS_HALF_SATURATION,
                MIN_RING_RADIUS_HALF_SATURATION, MAX_RING_RADIUS_HALF_SATURATION);

        builder.pop();
        SPEC = builder.build();
    }

    private ToolVisualConfig() {}

    public static void attach(IEventBus modBus) {
        modBus.addListener(ToolVisualConfig::onLoading);
        modBus.addListener(ToolVisualConfig::onReloading);
    }

    private static void onLoading(ModConfigEvent.Loading event) {
        refresh(event);
    }

    private static void onReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        pulseTicks = PULSE_TICKS.get();
        attackRingTicks = ATTACK_RING_TICKS.get();
        pulseScale = sanitizeDouble(PULSE_SCALE.get(), DEFAULT_PULSE_SCALE,
            MIN_PULSE_SCALE, MAX_PULSE_SCALE);
        spinPeriodMin = SPIN_PERIOD_MIN.get();
        spinPeriodMax = SPIN_PERIOD_MAX.get();
        spinPeriodDivisor = sanitizeDouble(SPIN_PERIOD_DIVISOR.get(), DEFAULT_SPIN_PERIOD_DIVISOR,
            MIN_SPIN_PERIOD_DIVISOR, MAX_SPIN_PERIOD_DIVISOR);
        returnTicks = RETURN_TICKS.get();
        ringRadiusMax = sanitizeDouble(RING_RADIUS_MAX.get(), DEFAULT_RING_RADIUS_MAX,
            MIN_RING_RADIUS_MAX, MAX_RING_RADIUS_MAX);
        ringRadiusHalfSaturation = sanitizeDouble(RING_RADIUS_HALF_SATURATION.get(),
            DEFAULT_RING_RADIUS_HALF_SATURATION,
            MIN_RING_RADIUS_HALF_SATURATION, MAX_RING_RADIUS_HALF_SATURATION);
    }

    static float sanitizeDouble(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) value = fallback;
        return (float) Math.clamp(value, min, max);
    }

    // ── 读路径（渲染热路径，只读 volatile 字段）──────────────────────────────

    public static int pulseTicks() { return pulseTicks; }

    public static int attackRingTicks() { return attackRingTicks; }

    public static float pulseScale() { return pulseScale; }

    public static int spinPeriodMin() { return spinPeriodMin; }

    public static int spinPeriodMax() { return spinPeriodMax; }

    public static float spinPeriodDivisor() { return spinPeriodDivisor; }

    public static int returnTicks() { return returnTicks; }

    public static float ringRadiusMax() { return ringRadiusMax; }

    public static float ringRadiusHalfSaturation() { return ringRadiusHalfSaturation; }

    // ── 测试钩子 ────────────────────────────────────────────────────────────

    /** 测试钩子（跨包可见）：渲染器测试在 client 包，需要直接改配置验证联动。 */
    public static void applyForTest(int pulseTicks, int attackRingTicks, float pulseScale,
                                    int spinPeriodMin, int spinPeriodMax, float spinPeriodDivisor,
                                    int returnTicks, float ringRadiusMax, float ringRadiusHalfSat) {
        ToolVisualConfig.pulseTicks = pulseTicks;
        ToolVisualConfig.attackRingTicks = attackRingTicks;
        ToolVisualConfig.pulseScale = pulseScale;
        ToolVisualConfig.spinPeriodMin = spinPeriodMin;
        ToolVisualConfig.spinPeriodMax = spinPeriodMax;
        ToolVisualConfig.spinPeriodDivisor = spinPeriodDivisor;
        ToolVisualConfig.returnTicks = returnTicks;
        ToolVisualConfig.ringRadiusMax = ringRadiusMax;
        ToolVisualConfig.ringRadiusHalfSaturation = ringRadiusHalfSat;
    }

    /** 测试钩子（跨包可见）：恢复全部默认值。 */
    public static void resetForTest() {
        applyForTest(DEFAULT_PULSE_TICKS, DEFAULT_ATTACK_RING_TICKS, (float) DEFAULT_PULSE_SCALE,
            DEFAULT_SPIN_PERIOD_MIN, DEFAULT_SPIN_PERIOD_MAX, (float) DEFAULT_SPIN_PERIOD_DIVISOR,
            DEFAULT_RETURN_TICKS, (float) DEFAULT_RING_RADIUS_MAX,
            (float) DEFAULT_RING_RADIUS_HALF_SATURATION);
    }
}
