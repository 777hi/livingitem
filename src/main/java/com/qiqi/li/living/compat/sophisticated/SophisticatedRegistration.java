package com.qiqi.li.living.compat.sophisticated;

import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.container.MenuSlotOwners;
import com.qiqi.li.living.interaction.MenuActionAccess;

/** 精妙背包联动的注册式接线；加载门面只负责模组存在性。 */
public final class SophisticatedRegistration {
    private SophisticatedRegistration() {}

    public static void register() {
        MenuSlotOwners.registerMenuResolver(new SophisticatedMenuResolver());
        MenuActionAccess.register(new SophisticatedMenuAccess());
        ItemFormContainers.register(new SophisticatedBackpackSource());
    }
}
