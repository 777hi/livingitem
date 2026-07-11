package com.qiqi.li.living.core.interaction;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
 * 使用示例：
 * <pre>
 * // 活TNT声明"可被活打火石右键点燃"
 * new InteractionEntry(
 *     Items.TNT,              // 目标物品（我是谁）
 *     Items.FLINT_AND_STEEL,  // 触发物品（谁来交互）
 *     1,                      // 鼠标按键（右键）
 *     "ignite"                // 动作ID
 * )
 * </pre>
 */
public record InteractionEntry(
    Item targetItem,
    Item triggerItem,
    int button,
    String actionId
) {
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
        return !stack.isEmpty() && stack.is(triggerItem);
    }
}