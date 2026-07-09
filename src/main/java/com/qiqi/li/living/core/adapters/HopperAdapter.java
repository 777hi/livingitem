package com.qiqi.li.living.core.adapters;

import com.qiqi.li.living.core.model.Pos2D;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.HashMap;
import java.util.Map;

/**
 * 漏斗容器适配器 —— 处理 5 格漏斗容器的特殊槽位布局。
 *
 * 漏斗的 5 格布局与标准 9 列网格不同：
 * ┌───┬───┬───┬───┬───┐
 * │ 0 │ 1 │ 2 │ 3 │ 4 │
 * └───┴───┴───┴───┴───┘
 *   输入 ← 筛选 → 输出
 *
 * 特殊规则：
 * - 只有中间 3 格（1,2,3）可以作为活物品宿主位置
 * - 第 0 格（输入）只能向右移动
 * - 第 4 格（输出）只能向左移动
 * - 中间格可以左右移动
 * - 不支持上下方向（漏斗是线性布局）
 */
public class HopperAdapter implements ContainerAdapter {

    /** 单例实例 */
    public static final HopperAdapter INSTANCE = new HopperAdapter();

    /** 漏斗容器大小（固定为 5） */
    private static final int HOPPER_SIZE = 5;

    /**
     * 判断是否支持此容器类型。
     * 仅支持 Minecraft 原版漏斗方块实体。
     */
    @Override
    public boolean supports(Container container) {
        return container instanceof HopperBlockEntity;
    }

    /**
     * 获取漏斗的容器布局信息。
     *
     * 返回线性布局，包含每个槽位的详细信息：
     * - 第 0 格：边缘槽位，只能向右
     * - 第 1-3 格：中间槽位，可以左右移动
     * - 第 4 格：边缘槽位，只能向左
     */
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

    /**
     * 解析漏斗中的槽位索引。
     *
     * 使用简单的线性偏移：
     * - LEFT: hostSlot - 1（不能小于 0）
     * - RIGHT: hostSlot + 1（不能超过 4）
     * - 其他方向返回 -1（不支持）
     *
     * @param container 漏斗容器
     * @param hostSlot 宿主活物品所在槽位
     * @param direction 方向偏移
     * @return 目标槽位索引，无效返回 -1
     */
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

    /**
     * 验证宿主位置是否有效。
     *
     * 漏斗只有中间 3 格（1,2,3）可以作为活物品宿主，
     * 因为输入格和输出格需要保持原功能。
     *
     * @param container 漏斗容器
     * @param slot 要验证的槽位
     * @return 如果在 [1,3] 范围内返回 true
     */
    @Override
    public boolean isValidHostPosition(Container container, int slot) {
        if (slot < 0 || slot >= HOPPER_SIZE) return false;
        return slot >= 1 && slot <= 3;
    }
}