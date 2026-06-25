package com.qiqi.li.living;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class SimpleContainerContext implements ContainerContext {
    private final Container container;
    private final String containerKey;

    public SimpleContainerContext(Container container) {
        this.container = container;
        this.containerKey = buildContainerKey(container);
    }

    private static String buildContainerKey(Container container) {
        if (container instanceof Inventory inv) {
            return "player_" + inv.player.getStringUUID();
        }
        if (container instanceof BlockEntity be) {
            Level level = be.getLevel();
            BlockPos pos = be.getBlockPos();
            String dimKey = level != null ? level.dimension().location().toString() : "unknown";
            return "blockentity_" + dimKey + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
        return "container_" + Integer.toHexString(container.hashCode());
    }

    @Override
    public int getSize() {
        return container.getContainerSize();
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        return container.getItem(logicalSlot);
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        container.setItem(logicalSlot, stack);
    }

    @Override
    public int getMaxStackSize() {
        return container.getMaxStackSize();
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "_slot_" + logicalSlot + "_func_" + functionId;
    }
}