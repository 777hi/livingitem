package com.qiqi.li.living.interaction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;

/**
 * GUI交互规则条目 —— 声明一条"光标物品A + 按键 → 槽位物品B → 动作"的交互规则。
 *
 * 每种活物品通过 LivingFunctionConfig.addInteraction() 注册交互规则，
 * 由 InteractionRegistry 统一管理查询。
 *
 * 匹配流程：
 *   客户端检测到鼠标按键 → 查询 InteractionRegistry →
 *   匹配 trigger + target + button → 发送 GuiInteractionPacket(actionId) →
 *   服务端通过 actionId 查找 InteractionHandler 执行逻辑
 *
 * 自交互（self-interaction）：
 *   当 triggerItem 为 null 时，表示目标物品可以被任意光标物品（包括空手）右键触发。
 *   适用于按钮按压、拉杆切换等自激活场景。
 *
 * 使用示例：
 * <pre>
 * // 活TNT声明"可被活打火石右键点燃"
 * new InteractionEntry(
 *     Items.TNT,              // 目标物品（我是谁）
 *     Items.FLINT_AND_STEEL,  // 触发物品（谁来交互）
 *     1,                      // 鼠标按键（右键）
 *     "ignite"                // 动作ID
 * )
 *
 * // 活按钮声明"右键自身按压"
 * new InteractionEntry(
 *     Items.STONE_BUTTON,     // 目标物品
 *     null,                   // 任意触发（自交互）
 *     1,                      // 右键
 *     "button_press"          // 动作ID
 * )
 * </pre>
 */
public record InteractionEntry(
    Item targetItem,
    @Nullable Item triggerItem,
    int button,
    String actionId,
    boolean onRelease
) {
    public InteractionEntry(Item targetItem, @Nullable Item triggerItem, int button, String actionId) {
        this(targetItem, triggerItem, button, actionId, false);
    }
    /**
     * 判断给定的槽位物品是否匹配此交互的目标。
     * 要求物品类型匹配且为活物品。
     */
    public boolean matchesTarget(ItemStack stack) {
        return !stack.isEmpty() && stack.is(targetItem);
    }

    /**
     * 判断给定的光标物品是否匹配此交互的触发器。
     * 要求物品类型匹配且为活物品。
     */
    public boolean matchesTrigger(ItemStack stack) {
        if (triggerItem == null) return true;
        return !stack.isEmpty() && stack.is(triggerItem);
    }
}