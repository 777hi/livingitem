package com.qiqi.li.living.interaction;

import java.util.Set;
import javax.annotation.Nullable;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 动作的执行体与授权范围；玩法匹配规则不能改变访问语义，执行体仅供授权后调用。 */
public record InteractionAction(Kind kind, InteractionHandler handler, ScopeResolver scopeResolver) {
    public enum Kind { ITEM_STATE, CONTAINER_STATE, USE_CONTAINER }

    /** 非空逻辑槽集合属于同一真实上下文；物品动作只授权精确目标槽。 */
    public record Scope(@Nullable ContainerContext context, Set<Integer> logicalSlots) {
        public Scope {
            logicalSlots = Set.copyOf(logicalSlots);
        }
    }

    /** 仅计算候选范围，不写状态、不扣光标、不触发世界动作；null 表示不受理。 */
    @FunctionalInterface
    public interface ScopeResolver {
        @Nullable Scope resolve(ServerPlayer player, Slot target, ItemStack carried);
    }

    public static InteractionAction item(InteractionHandler handler) {
        return new InteractionAction(Kind.ITEM_STATE, handler,
            (player, slot, carried) -> new Scope(null, Set.of()));
    }

    public static InteractionAction container(InteractionHandler handler) {
        return new InteractionAction(Kind.CONTAINER_STATE, handler, (player, slot, carried) -> {
            ContainerContext ctx = ContainerContexts.resolve(player, slot);
            if (ctx == null) return null;
            int logical = ctx.toLogicalSlot(slot.getContainerSlot());
            return ctx.isValidSlot(logical) ? new Scope(ctx, Set.of(logical)) : null;
        });
    }

    public boolean permits(ServerPlayer player, Slot target, ItemStack carried) {
        if (kind == Kind.ITEM_STATE && (target.getItem().isEmpty()
                || !com.qiqi.li.living.api.LivingItemManager.isLivingItem(target.getItem()))) return false;
        if (!MenuActionAccess.validTarget(player, target)) return false;
        Scope scope = scopeResolver.resolve(player, target, carried);
        return scope != null && MenuActionAccess.check(new MenuActionAccess.Request(
            player, player.containerMenu, target, kind, scope.context(), scope.logicalSlots()))
            == MenuActionAccess.Decision.ALLOW;
    }
}
