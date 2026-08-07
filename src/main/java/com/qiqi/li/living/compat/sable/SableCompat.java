package com.qiqi.li.living.compat.sable;

import net.neoforged.fml.ModList;

public final class SableCompat {

    private static Boolean loaded;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("sable");
        }
        return loaded;
    }

    private SableCompat() {}
}