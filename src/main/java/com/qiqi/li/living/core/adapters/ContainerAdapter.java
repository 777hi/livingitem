package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * 容器适配器接口 —— 为非标准容器提供自定义槽位解析逻辑。
 *
 * 适用于：
 * - 特殊布局的模组容器（非 9 列网格）
 * - 需要复杂槽位映射的容器（如漏斗的线性布局）
 * - 有特殊规则的容器（如某些格不能作为宿主）
 *
 * 实现规范：
 * - supports() 应快速返回，避免在每次调用时做复杂检查
 * - resolveSlot() 必须处理边界情况（越界返回 -1）
 * - isValidHostPosition() 应考虑容器的实际使用规则
 */
public interface ContainerAdapter {

    /**
     * 判断此适配器是否支持指定的容器。
     *
     * @param container 要检查的容器
     * @return 如果支持返回 true
     */
    boolean supports(Container container);

    /**
     * 获取容器的布局信息。
     *
     * 返回包含容器大小、行列数、每个槽位详细信息的布局对象。
     *
     * @param container 目标容器
     * @return 容器布局信息
     */
    ContainerLayout getLayout(Container container);

    /**
     * 解析指定方向的槽位索引。
     *
     * @param container 目标容器
     * @param hostSlot 宿主活物品所在槽位
     * @param direction 方向偏移
     * @return 目标槽位索引，无效或越界返回 -1
     */
    int resolveSlot(Container container, int hostSlot, Pos2D direction);

    /**
     * 验证指定位置是否可以作为活物品的宿主位置。
     *
     * 某些容器的特定槽位可能有特殊用途，
     * 不能放置活物品（如漏斗的输入/输出格）。
     *
     * @param container 目标容器
     * @param slot 要验证的槽位
     * @return 如果可以作为宿主位置返回 true
     */
    boolean isValidHostPosition(Container container, int slot);

    /**
     * 容器布局描述 —— 包含容器的结构信息和每个槽位的详细信息。
     *
     * @param containerSize 容器总大小
     * @param type 布局类型（标准矩形、自定义矩形、线性、不规则）
     * @param rows 行数
     * @param columns 列数
     * @param slotDetails 每个槽位的详细信息映射（逻辑索引 → 槽位信息）
     */
    record ContainerLayout(
        int containerSize,
        LayoutType type,
        int rows,
        int columns,
        Map<Integer, SlotInfo> slotDetails
    ) {}

    /**
     * 布局类型枚举。
     *
     * RECTANGULAR_STANDARD: 标准 9 列矩形布局（箱子、背包等）
     * RECTANGULAR_CUSTOM: 自定义列宽的矩形布局
     * LINEAR: 线性布局（单行，如漏斗）
     * IRREGULAR: 不规则布局（无法用简单行列表示）
     */
    enum LayoutType {
        /** 标准矩形（9 列） */
        RECTANGULAR_STANDARD(9),
        /** 自定义矩形（可变列宽） */
        RECTANGULAR_CUSTOM(3),
        /** 线性布局（单行） */
        LINEAR(1),
        /** 不规则布局（无固定行列） */
        IRREGULAR(0);

        public final int defaultColumnWidth;
        LayoutType(int defaultColumnWidth) {
            this.defaultColumnWidth = defaultColumnWidth;
        }
    }

    /**
     * 槽位详细信息 —— 描述单个槽位的属性。
     *
     * @param logicalIndex 逻辑索引（在容器中的位置）
     * @param displayRow 显示行号（GUI 中的行位置）
     * @param displayCol 显示列号（GUI 中的列位置）
     * @param isEdgeSlot 是否为边缘槽位（可能受边界行为影响）
     * @param availableDirections 此槽位支持的有效方向列表
     */
    record SlotInfo(
        int logicalIndex,
        int displayRow,
        int displayCol,
        boolean isEdgeSlot,
        Pos2D[] availableDirections
    ) {}
}