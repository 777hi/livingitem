package com.qiqi.li.living.config;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** TLM联动的本机视觉配置；仅客户端注册，不同步服务器。 */
public final class TlmClientConfig {
    public static final String FILE_NAME = "living_item-tlm-client.toml";
    public static final double DEFAULT_PREVIEW_SCALE_MULTIPLIER = 1.25D;
    public static final double MIN_PREVIEW_SCALE_MULTIPLIER = 0.25D;
    public static final double MAX_PREVIEW_SCALE_MULTIPLIER = 4.0D;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.DoubleValue PREVIEW_SCALE_MULTIPLIER;

    /** 未加载配置时也使用新默认值；渲染热路径只读volatile primitive。 */
    private static volatile float previewScaleMultiplier = (float) DEFAULT_PREVIEW_SCALE_MULTIPLIER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Touhou Little Maid integration client visuals.")
                .translation("living_item.configuration.soul_slab")
                .push("soul_slab");
        PREVIEW_SCALE_MULTIPLIER = builder.comment(
                        "Extra multiplier for the maid model rendered by a living soul slab.",
                        "1.0 = legacy size; 1.25 = 25% larger.",
                        "Multiplies TLM model render_item_scale; client-only and hot-reloadable.")
                .translation("living_item.configuration.soul_slab.preview_model_scale_multiplier")
                .defineInRange("preview_model_scale_multiplier",
                        DEFAULT_PREVIEW_SCALE_MULTIPLIER,
                        MIN_PREVIEW_SCALE_MULTIPLIER,
                        MAX_PREVIEW_SCALE_MULTIPLIER);
        builder.pop();
        SPEC = builder.build();
    }

    private TlmClientConfig() {}

    public static void attach(IEventBus modBus) {
        modBus.addListener(TlmClientConfig::onLoading);
        modBus.addListener(TlmClientConfig::onReloading);
    }

    private static void onLoading(ModConfigEvent.Loading event) {
        refresh(event);
    }

    private static void onReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        previewScaleMultiplier = sanitize(PREVIEW_SCALE_MULTIPLIER.get());
    }

    public static float previewScaleMultiplier() {
        return previewScaleMultiplier;
    }

    static float sanitize(double value) {
        if (!Double.isFinite(value)) value = DEFAULT_PREVIEW_SCALE_MULTIPLIER;
        return (float) Math.clamp(value, MIN_PREVIEW_SCALE_MULTIPLIER, MAX_PREVIEW_SCALE_MULTIPLIER);
    }

    static void applyForTest(double value) {
        previewScaleMultiplier = sanitize(value);
    }

    static void resetForTest() {
        previewScaleMultiplier = (float) DEFAULT_PREVIEW_SCALE_MULTIPLIER;
    }
}
