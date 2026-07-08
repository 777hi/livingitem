package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.HashMap;
import java.util.Map;

public class HopperAdapter implements ContainerAdapter {

    public static final HopperAdapter INSTANCE = new HopperAdapter();

    private static final int HOPPER_SIZE = 5;

    @Override
    public boolean supports(Container container) {
        return container instanceof HopperBlockEntity;
    }

    @Override
    public ContainerLayout getLayout(Container container) {
        Map<Integer, SlotInfo> details = new HashMap<>();

        for (int i = 0; i < HOPPER_SIZE; i++) {
            boolean isEdge = (i == 0 || i == HOPPER_SIZE - 1);

            Pos2D[] availableDirs;
            if (i == 0) {
                availableDirs = new Pos2D[]{Pos2D.RIGHT};
            } else if (i == HOPPER_SIZE - 1) {
                availableDirs = new Pos2D[]{Pos2D.LEFT};
            } else {
                availableDirs = new Pos2D[]{Pos2D.LEFT, Pos2D.RIGHT};
            }

            details.put(i, new SlotInfo(
                i,
                0,
                i,
                isEdge,
                availableDirs
            ));
        }

        return new ContainerLayout(
            HOPPER_SIZE,
            LayoutType.LINEAR,
            1,
            HOPPER_SIZE,
            details
        );
    }

    @Override
    public int resolveSlot(Container container, int hostSlot, Pos2D direction) {
        if (hostSlot < 0 || hostSlot >= HOPPER_SIZE) return -1;

        if (direction == Pos2D.LEFT) {
            return hostSlot > 0 ? hostSlot - 1 : -1;
        } else if (direction == Pos2D.RIGHT) {
            return hostSlot < HOPPER_SIZE - 1 ? hostSlot + 1 : -1;
        } else {
            return -1;
        }
    }

    @Override
    public boolean isValidHostPosition(Container container, int slot) {
        if (slot < 0 || slot >= HOPPER_SIZE) return false;
        return slot >= 1 && slot <= 3;
    }
}