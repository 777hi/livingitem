package com.qiqi.li.living.domain.portal;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntPredicate;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 四邻围闭与已点燃区域校验；真实网格边缘、缺槽和换行都不是门框。 */
public final class PortalStructure {
    private PortalStructure() {}

    public static boolean isFrame(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.OBSIDIAN)
            && LivingItemManager.isLivingItem(stack);
    }

    /** 从非框槽查找完整围闭区域；失败返回空集合，不校验点火内容准入。 */
    public static Set<Integer> findEnclosed(ContainerContext ctx, int start) {
        int size = ctx.getSize();
        int width = ctx.getWidth();
        if (size <= 0 || width <= 0 || start < 0 || start >= size || isFrame(ctx.getItem(start))) {
            return Set.of();
        }
        Set<Integer> visited = new LinkedHashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        visited.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            int slot = pending.removeFirst();
            for (int direction = 0; direction < 4; direction++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, direction, size, width);
                if (neighbor < 0) return Set.of();
                if (!isFrame(ctx.getItem(neighbor)) && visited.add(neighbor)) pending.addLast(neighbor);
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    /** 点火要求整个候选区域实际空且无流体；谓词须包含实际流体与即时源。 */
    public static boolean canIgnite(ContainerContext ctx, Set<Integer> region, IntPredicate hasFluid) {
        if (!validate(ctx, region, hasFluid)) return false;
        for (int slot : region) {
            if (!ctx.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    /** 普通货不灭门；任一门面槽有活物品、流体或必要边界失效则整区域无效。 */
    public static boolean validate(ContainerContext ctx, Set<Integer> region, IntPredicate hasFluid) {
        int size = ctx.getSize();
        int width = ctx.getWidth();
        if (size <= 0 || width <= 0 || region == null || region.isEmpty()) return false;
        for (Integer slot : region) {
            if (slot == null || slot < 0 || slot >= size) return false;
            ItemStack stack = ctx.getItem(slot);
            if (LivingItemManager.isLivingItem(stack) || hasFluid.test(slot)) return false;
            for (int direction = 0; direction < 4; direction++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, direction, size, width);
                if (neighbor < 0 || (!region.contains(neighbor) && !isFrame(ctx.getItem(neighbor)))) return false;
            }
        }

        Set<Integer> visited = new LinkedHashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int start = region.iterator().next();
        pending.add(start);
        visited.add(start);
        while (!pending.isEmpty()) {
            int slot = pending.removeFirst();
            for (int direction = 0; direction < 4; direction++) {
                int neighbor = ContainerContext.resolveNeighbor(slot, direction, size, width);
                if (region.contains(neighbor) && visited.add(neighbor)) pending.addLast(neighbor);
            }
        }
        return visited.size() == region.size();
    }

    public static boolean validate(ContainerContext ctx, ContainerPortalData.PortalRegion region,
                                   IntPredicate hasFluid) {
        return region.width() == ctx.getWidth() && validate(ctx, region.slots(), hasFluid);
    }
}
