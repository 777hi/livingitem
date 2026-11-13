package com.qiqi.li.client.render.tlm;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/** 所存女仆的客户端展示设置；缺失或错型字段使用TLM默认值。 */
final class SoulSlabMaidSettings {
    private static final String ROOT = "MaidSubConfig";

    private SoulSlabMaidSettings() {}

    static boolean chatBubbleShow(CompoundTag maid) {
        CompoundTag config = config(maid);
        return config == null || !config.contains("ChatBubbleShow", Tag.TAG_BYTE)
                || config.getBoolean("ChatBubbleShow");
    }

    static float soundFrequency(CompoundTag maid) {
        CompoundTag config = config(maid);
        return config != null && config.contains("SoundFreq", Tag.TAG_FLOAT)
                ? Mth.clamp(config.getFloat("SoundFreq"), 0, 1) : 1;
    }

    private static CompoundTag config(CompoundTag maid) {
        return maid.contains(ROOT, Tag.TAG_COMPOUND) ? maid.getCompound(ROOT) : null;
    }
}
