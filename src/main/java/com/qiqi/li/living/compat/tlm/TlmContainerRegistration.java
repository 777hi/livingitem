package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.qiqi.li.living.container.EntityContainers;
import com.qiqi.li.living.container.MenuSlotOwners;
import net.neoforged.fml.ModList;

/** 双端宿主登记；无 TLM 时不链接适配实现。 */
public final class TlmContainerRegistration {
    private static boolean registered;

    private TlmContainerRegistration() {}

    public static synchronized void register() {
        if (registered || !ModList.get().isLoaded("touhou_little_maid")) return;
        Loaded.register();
        registered = true;
    }

    private static final class Loaded {
        private static void register() {
            EntityContainers.registerProvider(new MaidBackpackHost());
            MenuSlotOwners.registerEntityResolver((menu, slot) ->
                menu instanceof AbstractMaidContainer maidMenu ? maidMenu.getMaid() : null);
        }
    }
}
