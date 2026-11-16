package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.qiqi.li.living.container.EntityContainers;
import com.qiqi.li.living.container.MenuSlotOwners;
import com.qiqi.li.living.interaction.MenuActionAccess;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

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
            MaidBackpackHost host = new MaidBackpackHost();
            EntityContainers.registerProvider(host);
            MenuActionAccess.register(host);
            MenuSlotOwners.registerEntityResolver((menu, slot) ->
                menu instanceof AbstractMaidContainer maidMenu ? maidMenu.getMaid() : null);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TlmFavorRedemption::onToMaid);
            NeoForge.EVENT_BUS.addListener(TlmFavorRedemption::onServerTick);
        }
    }
}
