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

    /**
     * 【读数】读取某槽位当前配置的方向（可选，供客户端 HUD / tooltip 显示）。
     *
     * <p>返回 {@code null} 表示「本功能不提供读数」（HUD 显示为未知）；
     * 返回 {@code Pos2D.NONE} 表示「未配置」。二者语义不同，勿混淆。</p>
     *
     * @param stack 物品
     * @param slotName 槽位名（与 {@link #getDirectionSlotNames()} 对应）
     * @return 当前方向；null = 不提供读数
     */
    @javax.annotation.Nullable
    default Pos2D getSlotDirection(ItemStack stack, String slotName) {
        return null;
    }
}