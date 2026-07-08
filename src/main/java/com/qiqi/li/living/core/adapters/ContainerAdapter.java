package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.Map;

public interface ContainerAdapter {

    boolean supports(Container container);

    ContainerLayout getLayout(Container container);

    int resolveSlot(Container container, int hostSlot, Pos2D direction);

    boolean isValidHostPosition(Container container, int slot);

    record ContainerLayout(
        int containerSize,
        LayoutType type,
        int rows,
        int columns,
        Map<Integer, SlotInfo> slotDetails
    ) {}

    enum LayoutType {
        RECTANGULAR_STANDARD(9),
        RECTANGULAR_CUSTOM(3),
        LINEAR(1),
        IRREGULAR(0);

        public final int defaultColumnWidth;
        LayoutType(int defaultColumnWidth) {
            this.defaultColumnWidth = defaultColumnWidth;
        }
    }

    record SlotInfo(
        int logicalIndex,
        int displayRow,
        int displayCol,
        boolean isEdgeSlot,
        Pos2D[] availableDirections
    ) {}
}