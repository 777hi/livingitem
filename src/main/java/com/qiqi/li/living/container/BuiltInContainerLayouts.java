package com.qiqi.li.living.container;

import javax.annotation.Nullable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

/** 原版内建布局约定；未知类型或容量不得借用车船的固定值。 */
public final class BuiltInContainerLayouts {
    private static final ContainerLayout SINGLE = new ContainerLayout(1, ContainerLayout.Source.FIXED);
    private static final ContainerLayout INVENTORY = new ContainerLayout(9, ContainerLayout.Source.FIXED);
    private static final ContainerLayout HOPPER_VEHICLE = new ContainerLayout(5, ContainerLayout.Source.FIXED);
    private static final ContainerLayout FAMILY_SINGLE = new ContainerLayout(1, ContainerLayout.Source.FAMILY);
    private static final ContainerLayout FAMILY_THREE = new ContainerLayout(3, ContainerLayout.Source.FAMILY);
    private static final ContainerLayout FAMILY_FIVE = new ContainerLayout(5, ContainerLayout.Source.FAMILY);
    private static final ContainerLayout FAMILY_NINE = new ContainerLayout(9, ContainerLayout.Source.FAMILY);

    private BuiltInContainerLayouts() {}

    @Nullable
    public static ContainerLayout find(TickableContainerContext ctx) {
        if (ctx.getInventory() != null) return INVENTORY;
        int size = ctx.getSize();
        ContainerKind kind = ContainerKeys.kindOf(ctx.getContainerKey());
        if (kind == ContainerKind.ITEM_ENTITY && size == 1) return SINGLE;
        if (kind == ContainerKind.ENDER_CHEST && size == 27) return INVENTORY;
        var entity = ctx.getEntity();
        if (entity != null) {
            if (entity instanceof AbstractHorse && size <= 0) return SINGLE;
            var type = entity.getType();
            if ((type == EntityType.CHEST_BOAT || type == EntityType.CHEST_MINECART) && size == 27) return INVENTORY;
            if (type == EntityType.HOPPER_MINECART && size == 5) return HOPPER_VEHICLE;
        }
        for (var be : ctx.getAssociatedBlockEntities()) {
            if (be.getLevel() == null) continue;
            Container container = ContainerContext.getContainer(be.getLevel(), be.getBlockPos());
            if (container == null) continue;
            // 原解析在第一个可取得的 Container 上结束，不能借后续对象家族改变旧行为。
            return familyOf(container);
        }
        return null;
    }

    /** 方块家族约定保留 instanceof 语义，未知家族返回 -1。 */
    public static int columnsOf(Container container) {
        ContainerLayout family = familyOf(container);
        return family == null ? -1 : family.columns();
    }

    @Nullable
    private static ContainerLayout familyOf(Container container) {
        if (container instanceof ChestBlockEntity || container instanceof BarrelBlockEntity
                || container instanceof ShulkerBoxBlockEntity) return FAMILY_NINE;
        if (container instanceof HopperBlockEntity) return FAMILY_FIVE;
        if (container instanceof DispenserBlockEntity || container instanceof DropperBlockEntity) return FAMILY_THREE;
        if (container instanceof FurnaceBlockEntity || container instanceof BlastFurnaceBlockEntity
                || container instanceof SmokerBlockEntity || container instanceof BrewingStandBlockEntity) return FAMILY_SINGLE;
        return null;
    }
}
