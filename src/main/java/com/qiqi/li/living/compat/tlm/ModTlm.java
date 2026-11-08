package com.qiqi.li.living.compat.tlm;

import net.neoforged.fml.ModList;

/** 车万女仆软依赖探测；缺席时不加载其实现类。 */
public final class ModTlm {
    public static final String MOD_ID = "touhou_little_maid";

    private ModTlm() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }
}
