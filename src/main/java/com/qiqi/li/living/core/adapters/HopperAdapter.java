package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.Direction2D;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.HashMap;
import java.util.Map;

/**
 * 漏斗容器适配器
 * 
 * 漏斗的5个槽位是线性排列的：
 * [0][1][2][3][4]
 * 
 * 方向映射规则：
 * - LEFT: slot - 1 (如果 > 0)
 * - RIGHT: slot + 1 (如果 < 4)
 * - UP/DOWN: 无效（漏斗没有上下概念）
 */
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
            
            Direction2D[] availableDirs;
            if (i == 0) {
                availableDirs = new Direction2D[]{Direction2D.RIGHT};
            } else if (i == HOPPER_SIZE - 1) {
                availableDirs = new Direction2D[]{Direction2D.LEFT};
            } else {
                availableDirs = new Direction2D[]{Direction2D.LEFT, Direction2D.RIGHT};
            }
            
            details.put(i, new SlotInfo(
                i,           // logicalIndex
                0,           // displayRow (单行)
                i,           // displayCol
                isEdge,      // isEdgeSlot
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
    public int resolveSlot(Container container, int hostSlot, Direction2D direction) {
        if (hostSlot < 0 || hostSlot >= HOPPER_SIZE) return -1;

        switch (direction) {
            case LEFT:
                return hostSlot > 0 ? hostSlot - 1 : -1;
            case RIGHT:
                return hostSlot < HOPPER_SIZE - 1 ? hostSlot + 1 : -1;
            case UP:
            case DOWN:
            case NONE:
            default:
                return -1;  // 漏斗不支持上下方向
        }
    }

    @Override
    public boolean isValidHostPosition(Container container, int slot) {
        if (slot < 0 || slot >= HOPPER_SIZE) return false;
        
        // 中间位置（1-3）最适合放置活物品（左右都有空间）
        return slot >= 1 && slot <= 3;
    }
}