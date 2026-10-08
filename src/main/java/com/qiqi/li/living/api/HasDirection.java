package com.qiqi.li.living.api;

import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.model.Pos2D;

/**
 * 可定向功能接口。
 *
 * <p>标记某功能拥有「方向槽位」（如输入 / 输出 / 燃料方向）。由 {@link #getDirectionKeyCount} 与
 * {@link #getDirectionSlotNames} 描述槽位数量与名称，{@link #updateSlotDirection} 更新某槽位的方向
 * （{@code Pos2D}），供玩家在 GUI 中旋转功能朝向。</p>
 */
public interface HasDirection {

    int getDirectionKeyCount();

    String[] getDirectionSlotNames();

    boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction);
}