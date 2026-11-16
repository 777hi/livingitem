package com.qiqi.li.living.interaction;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.logging.ModLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** 当前菜单动作的访问边界；身份、物品存取与状态授权互不替代。 */
public final class MenuActionAccess {
    public enum Decision { ALLOW, DENY, UNKNOWN }

    public record Request(ServerPlayer player, AbstractContainerMenu menu, Slot target,
                          InteractionAction.Kind kind, @Nullable ContainerContext context,
                          Set<Integer> logicalSlots) {
        public Request {
            logicalSlots = Set.copyOf(logicalSlots);
        }
    }

    /** 认领与查询分开；认领后的拒绝、未知或异常均终止，不继续通用回退。 */
    public interface Resolver {
        boolean supports(AbstractContainerMenu menu);
        Decision check(Request request);
    }

    private static final List<Resolver> RESOLVERS = new CopyOnWriteArrayList<>();

    private MenuActionAccess() {}

    public static void register(Resolver resolver) {
        RESOLVERS.add(resolver);
    }

    /** 生命周期与精确槽身份门，不证明动作权限。 */
    public static boolean validTarget(ServerPlayer player, Slot slot) {
        AbstractContainerMenu menu = player.containerMenu;
        return menu != null && slot != null && slot.isActive() && menu.stillValid(player)
            && slot.index >= 0 && slot.index < menu.slots.size() && menu.getSlot(slot.index) == slot;
    }

    public static Decision check(Request request) {
        if (request.menu() != request.player().containerMenu
                || !validTarget(request.player(), request.target())) return Decision.DENY;
        if (!request.logicalSlots().isEmpty() && !validScope(request)) return Decision.DENY;
        for (Resolver resolver : RESOLVERS) {
            try {
                if (!resolver.supports(request.menu())) continue;
                Decision decision = resolver.check(request);
                return report(request, decision == null ? Decision.UNKNOWN : decision);
            } catch (RuntimeException e) {
                ModLog.CONTAINER.debug("菜单动作访问解析器 {} 失败：{}",
                    resolver.getClass().getName(), e.toString());
                return report(request, Decision.UNKNOWN);
            }
        }
        return report(request, standardAccess(request));
    }

    private static boolean validScope(Request request) {
        ContainerContext ctx = request.context();
        if (ctx == null) return false;
        int target = ctx.toLogicalSlot(request.target().getContainerSlot());
        if (!request.logicalSlots().contains(target)
                || !request.logicalSlots().stream().allMatch(ctx::isValidSlot)) return false;
        ContainerContext owner = ContainerContexts.resolve(request.player(), request.target());
        if (owner == null || !owner.getContainerKey().equals(ctx.getContainerKey())
                || owner.getLevel() != ctx.getLevel()) return false;
        if (scopeSlot(request, target) != request.target()) return false;
        return request.logicalSlots().stream().allMatch(logical -> scopeSlot(request, logical) != null);
    }

    /** 未覆写 Slot.mayPickup 的原版契约；抽插结果与物品过滤不证明状态授权。 */
    private static Decision standardAccess(Request request) {
        if (!standardSlot(request.player(), request.target())) return Decision.UNKNOWN;
        if (request.logicalSlots().isEmpty()) return Decision.ALLOW;
        for (int logical : request.logicalSlots()) {
            Slot mapped = scopeSlot(request, logical);
            if (mapped == null || !standardSlot(request.player(), mapped)) return Decision.UNKNOWN;
        }
        return Decision.ALLOW;
    }

    /** 原版状态授权证明；只认合法存储槽与未覆写的 Slot.mayPickup，不执行抽取查询。 */
    public static boolean standardSlot(ServerPlayer player, Slot slot) {
        int physical = slot.getContainerSlot();
        if (physical < 0 || physical >= slot.container.getContainerSize()
                || !slot.container.stillValid(player)) return false;
        try {
            return slot.getClass().getMethod("mayPickup", Player.class).getDeclaringClass() == Slot.class;
        } catch (NoSuchMethodException | SecurityException e) {
            return false;
        }
    }

    /** 点击格使用已证明身份的精确显示槽；其它范围格仍须唯一映射。 */
    @Nullable
    public static Slot scopeSlot(Request request, int logical) {
        ContainerContext ctx = request.context();
        if (ctx == null || !ctx.isValidSlot(logical)) return null;
        int physical = ctx.toPhysicalSlot(logical);
        Slot target = request.target();
        if (logical == ctx.toLogicalSlot(target.getContainerSlot())) {
            if (request.menu() != request.player().containerMenu
                    || !validTarget(request.player(), target) || target.getContainerSlot() != physical) return null;
            ContainerContext owner = ContainerContexts.resolve(request.player(), target);
            return owner != null && owner.getContainerKey().equals(ctx.getContainerKey())
                && owner.getLevel() == ctx.getLevel() ? target : null;
        }
        Slot found = null;
        for (Slot slot : request.menu().slots) {
            if (!slot.isActive() || slot.getContainerSlot() != physical) continue;
            ContainerContext owner = ContainerContexts.resolve(request.player(), slot);
            if (owner == null || !owner.getContainerKey().equals(ctx.getContainerKey())
                    || owner.getLevel() != ctx.getLevel()) continue;
            if (found != null) return null;
            found = slot;
        }
        return found;
    }

    private static Decision report(Request request, Decision decision) {
        if (decision != Decision.ALLOW) {
            ModLog.CONTAINER.debug("菜单动作拒绝：menu={} slot={} kind={} access={}",
                request.menu().getClass().getName(), request.target().index, request.kind(), decision);
        }
        return decision;
    }

    static void clearForTest() {
        RESOLVERS.clear();
    }
}
