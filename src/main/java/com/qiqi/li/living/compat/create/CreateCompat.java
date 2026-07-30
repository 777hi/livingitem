package com.qiqi.li.living.compat.create;

import net.neoforged.fml.ModList;

public final class CreateCompat {

    private static Boolean loaded;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("create");
        }
        return loaded;
    }

    private CreateCompat() {}
}