package com.qiqi.li.living.domain.redstone.piston;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.model.Pos2D;

import net.minecraft.world.item.Items;

/** 同容器有限链解析；范围内的漏斗始终是货物，不提前接管。 */
public final class PistonChainResolver {

    private PistonChainResolver() {}

    public static PistonPlan resolve(ContainerContext ctx, int hostSlot, Pos2D direction, int force) {
        if (!ctx.isValidSlot(hostSlot) || ctx.getWidth() < 1) {
            return new PistonPlan.Blocked(PistonPlan.Reason.INVALID_ADDRESS);
        }
        int edge = edgeDirection(direction);
        if (edge < 0) return new PistonPlan.Blocked(PistonPlan.Reason.INVALID_DIRECTION);
        if (force < 1) return new PistonPlan.Blocked(PistonPlan.Reason.INVALID_FORCE);

        List<Integer> cargo = new ArrayList<>();
        int slot = hostSlot;
        for (int distance = 0; distance < force; distance++) {
            slot = ContainerContext.resolveNeighbor(slot, edge, ctx.getSize(), ctx.getWidth());
            if (slot < 0) return new PistonPlan.Blocked(PistonPlan.Reason.BOUNDARY);
            if (ctx.getItem(slot).isEmpty()) {
                return cargo.isEmpty() ? new PistonPlan.EmptyPush() : new PistonPlan.Push(cargo, slot);
            }
            cargo.add(slot);
        }

        int next = ContainerContext.resolveNeighbor(slot, edge, ctx.getSize(), ctx.getWidth());
        if (next < 0) return new PistonPlan.Blocked(PistonPlan.Reason.BOUNDARY);
        var stack = ctx.getItem(next);
        if (stack.isEmpty()) return new PistonPlan.Push(cargo, next);
        if (stack.is(Items.HOPPER) && LivingItemManager.isLivingItem(stack)) {
            return new PistonPlan.Receiver(cargo, next);
        }
        return new PistonPlan.Blocked(PistonPlan.Reason.CHAIN_TOO_LONG);
    }

    /** 使用值相等：编解码得到的方向不保证是常量实例。 */
    static int edgeDirection(Pos2D direction) {
        if (Pos2D.UP.equals(direction)) return ContainerContext.E_UP;
        if (Pos2D.DOWN.equals(direction)) return ContainerContext.E_DOWN;
        if (Pos2D.LEFT.equals(direction)) return ContainerContext.E_LEFT;
        if (Pos2D.RIGHT.equals(direction)) return ContainerContext.E_RIGHT;
        return -1;
    }
}
