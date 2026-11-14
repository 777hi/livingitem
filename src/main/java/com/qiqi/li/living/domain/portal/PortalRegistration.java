package com.qiqi.li.living.domain.portal;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerHandoff;
import com.qiqi.li.living.container.ContainerTickHooks;
import com.qiqi.li.living.interaction.InteractionRegistry;

/** 活地狱门领域的唯一功能与交互注册入口。 */
public final class PortalRegistration {
    private PortalRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingPortalFunction());
        ContainerTickHooks.register(new PortalServerSync());
        InteractionRegistry.registerHandler(PortalInteractHandlers.IGNITE_ID, PortalInteractHandlers.IGNITE);
        InteractionRegistry.registerHandler(PortalInteractHandlers.USE_ID, PortalInteractHandlers.USE);
        ContainerHandoff.registerMover(new ContainerHandoff.Mover() {
            @Override
            public void moveOut(net.neoforged.neoforge.attachment.IAttachmentHolder host,
                                net.minecraft.world.item.ItemStack target) {
                PortalDataMigration.moveOut(host, target);
            }

            @Override
            public void moveIn(net.neoforged.neoforge.attachment.IAttachmentHolder host,
                               net.minecraft.world.item.ItemStack source) {
                PortalDataMigration.moveIn(host, source);
            }
        });
    }
}
