package com.qiqi.li.living.domain.portal;

import java.util.List;
import java.util.function.IntPredicate;

import com.qiqi.li.living.api.HasContainerData;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 容器级门面维护；没有框材时仍须清除失效门面。 */
public final class LivingPortalFunction implements LivingItemFunction, HasContainerData {
    public static final String ID = "living_nether_portal";

    @Override
    public boolean canApply(ItemStack stack) {
        return false;
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public boolean shouldTickWithoutOwnItems(ContainerContext ctx) {
        return true;
    }

    @Override
    public int getPriority() {
        return 4;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext ctx, TickContext tick, Level level) {
    }

    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerPortalData data = maintain(ctx, tick);
        if (!data.isEmpty()) {
            for (int slot : data.portalSlots()) {
                if (ctx.getItem(slot).isEmpty()) continue;
                data = maintain(ctx, tick);
                if (!data.contains(slot)) continue;
                var origin = PortalPositionResolver.resolve(ctx);
                if (origin.isEmpty()) break;
                PortalItemTransfer.transfer(ctx, slot, origin.get());
            }
        }
    }

    public static ContainerPortalData maintain(ContainerContext ctx, TickContext tick) {
        ContainerPortalData before = PortalDataAccess.getOrLoad(ctx);
        if (before.isEmpty()) return before;
        ContainerPortalData after = before;
        boolean hasPosition = PortalPositionResolver.resolve(ctx).isPresent();
        IntPredicate fluid = fluidAt(tick);
        for (var region : before.regions()) {
            if (!hasPosition || region.width() != ctx.getWidth()
                    || !PortalStructure.validate(ctx, region.slots(), fluid)) {
                after = after.withoutRegion(region);
            }
        }
        if (!after.equals(before)) PortalDataAccess.persist(ctx, after);
        return after;
    }

    public static IntPredicate fluidAt(TickContext tick) {
        var presence = tick.getFluidPresence();
        var ledger = tick.getSourceLedger();
        return slot -> presence != null && presence.hasFluidAt(slot)
            || ledger != null && ledger.sourceAt(slot) != null;
    }
}
