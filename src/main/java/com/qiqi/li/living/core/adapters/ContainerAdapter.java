package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.Direction2D;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * 容器适配器接口 - 用于处理非标准容器
 * 
 * 某些容器的槽位布局不是标准的9列网格，例如：
 * - 漏斗(Hopper): 5个槽位，线性排列
 * - 储物抽屉(Storage Drawers): 2-16个不规则槽位
 * - 自定义GUI容器: 特殊布局
 * 
 * 通过实现此接口，可以为这些容器提供正确的槽位映射逻辑
 */
public interface ContainerAdapter {

    /**
     * 判断此适配器是否支持给定的容器类型
     */
    boolean supports(Container container);

    /**
     * 获取容器的有效布局信息
     */
    ContainerLayout getLayout(Container container);

    /**
     * 根据自定义布局解析相对方向
     * 
     * @param hostSlot 活物品所在槽位
     * @param direction 相对方向
     * @return 对应的物理槽位索引，如果无效返回-1
     */
    int resolveSlot(Container container, int hostSlot, Direction2D direction);

    /**
     * 验证指定位置是否适合放置活物品
     */
    boolean isValidHostPosition(Container container, int slot);

    record ContainerLayout(
        int containerSize,
        LayoutType type,
        int rows,
        int columns,
        Map<Integer, SlotInfo> slotDetails
    ) {}

    enum LayoutType {
        RECTANGULAR_STANDARD(9),   // 标准9列网格（箱子、大箱子）
        RECTANGULAR_CUSTOM(3),     // 自定义矩形网格（投掷器3x3）
        LINEAR(1),                 // 线性排列（漏斗）
        IRREGULAR(0);              // 不规则布局（抽屉、自定义）

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
        Direction2D[] availableDirections
    ) {}
}