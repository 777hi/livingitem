package com.qiqi.li.living.domain.portal;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.interaction.InteractionHandler;
import com.qiqi.li.living.interaction.InteractionAction;
import com.qiqi.li.living.interaction.MenuActionAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 门面交互；客户端门面仅用于命中，服务端始终重新校验。 */
public final class PortalInteractHandlers {
    public static final String IGNITE_ID = "living_portal_ignite";
    public static final String USE_ID = "living_portal_use";
    public static final InteractionHandler IGNITE = (player, slot) -> {
        if (PortalInteractHandlers.IGNITE_ACTION.permits(player, slot, player.containerMenu.getCarried())) {
            ignite(player, slot);
        }
    };
    public static final InteractionHandler USE = (player, slot) -> {
        if (PortalInteractHandlers.USE_ACTION.permits(player, slot, player.containerMenu.getCarried())) {
            use(player, slot);
        }
    };
    public static final InteractionAction IGNITE_ACTION = new InteractionAction(
        InteractionAction.Kind.CONTAINER_STATE, PortalInteractHandlers::ignite, PortalInteractHandlers::igniteScope);
    public static final InteractionAction USE_ACTION = new InteractionAction(
        InteractionAction.Kind.USE_CONTAINER, PortalInteractHandlers::use, PortalInteractHandlers::useScope);

    private PortalInteractHandlers() {}

    public static boolean isPortalAction(String action) {
        return IGNITE_ID.equals(action) || USE_ID.equals(action);
    }

    public static boolean isIgniter(ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL) && LivingItemManager.isLivingItem(stack);
    }

    private static InteractionAction.Scope igniteScope(ServerPlayer player, Slot slot, ItemStack carried) {
        if (!isIgniter(carried) || !slot.getItem().isEmpty()) return null;
        ContainerContext ctx = context(player, slot);
        if (ctx == null || !supportsDimension(ctx)) return null;
        var region = PortalStructure.findEnclosed(ctx, ctx.toLogicalSlot(slot.getContainerSlot()));
        return region.isEmpty() ? null : new InteractionAction.Scope(ctx, region);
    }

    private static InteractionAction.Scope useScope(ServerPlayer player, Slot slot, ItemStack carried) {
        if (!carried.isEmpty()) return null;
        ContainerContext ctx = context(player, slot);
        if (ctx == null || PortalPositionResolver.resolve(ctx).isEmpty()) return null;
        int logical = ctx.toLogicalSlot(slot.getContainerSlot());
        for (var region : PortalDataAccess.peekSaved(ctx).regions()) {
            if (region.slots().contains(logical)) return new InteractionAction.Scope(ctx, region.slots());
        }
        return null;
    }

    private static void ignite(ServerPlayer player, Slot slot) {
        ItemStack carried = player.containerMenu.getCarried();
        if (!isIgniter(carried) || !slot.getItem().isEmpty()) return;
        ContainerContext ctx = context(player, slot);
        if (ctx == null || PortalPositionResolver.resolve(ctx).isEmpty()
                || !supportsDimension(ctx)) return;
        int logical = ctx.toLogicalSlot(slot.getContainerSlot());
        TickContext tick = new TickContext(ctx);
        ContainerPortalData data = LivingPortalFunction.maintain(ctx, tick);
        if (data.contains(logical)) return;
        var region = PortalStructure.findEnclosed(ctx, logical);
        if (!PortalStructure.canIgnite(ctx, region, LivingPortalFunction.fluidAt(tick))) return;
        PortalDataAccess.persist(ctx, data.withRegion(ctx.getWidth(), region));
        if (!player.hasInfiniteMaterials()) {
            ItemStack damaged = carried.copy();
            damaged.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            player.containerMenu.setCarried(damaged);
        }
        PortalServerSync.flush(ctx, tick);
    }

    private static void use(ServerPlayer player, Slot slot) {
        if (!player.containerMenu.getCarried().isEmpty()) return;
        ContainerContext ctx = context(player, slot);
        if (ctx == null) return;
        var origin = PortalPositionResolver.resolve(ctx);
        if (origin.isEmpty()) return;
        TickContext tick = new TickContext(ctx);
        var data = LivingPortalFunction.maintain(ctx, tick);
        int logical = ctx.toLogicalSlot(slot.getContainerSlot());
        if (!data.contains(logical)) {
            PortalServerSync.flush(ctx, tick);
            return;
        }
        PortalPlayerTeleport.teleport(player, origin.get());
    }

    /** 已有槽位交互可能倒入即时源；仅维护已存在门面，不触发货物发送。 */
    public static void afterInteraction(ServerPlayer player, Slot slot) {
        ContainerContext ctx = ContainerContexts.resolve(player, slot);
        if (ctx == null) return;
        var data = ctx.peekContainerData(ContainerPortalData.KEY);
        if (data == null || data.isEmpty()) return;
        TickContext tick = new TickContext(ctx);
        if (PortalPositionResolver.resolve(ctx).isPresent()) LivingPortalFunction.maintain(ctx, tick);
        PortalServerSync.flush(ctx, tick);
    }

    private static boolean supportsDimension(ContainerContext ctx) {
        var origin = PortalPositionResolver.resolve(ctx);
        return origin.isPresent() && (origin.get().level().dimension() == net.minecraft.world.level.Level.OVERWORLD
            || origin.get().level().dimension() == net.minecraft.world.level.Level.NETHER);
    }

    private static ContainerContext context(ServerPlayer player, Slot slot) {
        if (!MenuActionAccess.validTarget(player, slot)) return null;
        ContainerContext ctx = ContainerContexts.resolve(player, slot);
        if (ctx == null || !ctx.isValidSlot(ctx.toLogicalSlot(slot.getContainerSlot()))) return null;
        return ctx;
    }
}
