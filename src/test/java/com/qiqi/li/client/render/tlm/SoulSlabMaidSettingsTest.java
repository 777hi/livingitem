package com.qiqi.li.client.render.tlm;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabMaidSettingsTest {
    @Test
    void missingConfigUsesTlmDefaults() {
        CompoundTag maid = new CompoundTag();
        assertTrue(SoulSlabMaidSettings.chatBubbleShow(maid));
        assertEquals(1, SoulSlabMaidSettings.soundFrequency(maid));
    }

    @Test
    void savedPerMaidSettingsAreRestored() {
        CompoundTag maid = new CompoundTag();
        CompoundTag config = new CompoundTag();
        config.putBoolean("ChatBubbleShow", false);
        config.putFloat("SoundFreq", 0.4f);
        maid.put("MaidSubConfig", config);
        assertFalse(SoulSlabMaidSettings.chatBubbleShow(maid));
        assertEquals(0.4f, SoulSlabMaidSettings.soundFrequency(maid), 0.0001f);
    }

    @Test
    void soundFrequencyIsClampedAndWrongTypesUseDefaults() {
        CompoundTag maid = new CompoundTag();
        CompoundTag config = new CompoundTag();
        config.putFloat("SoundFreq", 5);
        maid.put("MaidSubConfig", config);
        assertEquals(1, SoulSlabMaidSettings.soundFrequency(maid));

        config.putString("SoundFreq", "invalid");
        config.putString("ChatBubbleShow", "invalid");
        assertEquals(1, SoulSlabMaidSettings.soundFrequency(maid));
        assertTrue(SoulSlabMaidSettings.chatBubbleShow(maid));
    }
}
