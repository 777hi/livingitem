package com.qiqi.li.living.interaction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.function.BiPredicate;
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
 * ⚠️ 通配条目的拦截代价（2026-09-13 实测反馈）：
 *   trigger=null 的通配条目会让客户端拦截「所有」右键——包括原版的拿起/放置/
 *   分堆等操作（客户端拦截发生在原版逻辑前，不匹配时 handler 静默返回，但原版
 *   点击已被取消，右键分堆等操作被吞）。若通配条目的实际语义是「某一类光标可触发」
 *   （如种植：任意可种植种子），<b>必须</b>提供 {@code triggerFilter} 把可触发光标
 *   收窄到该类物品——拦截面从「任意光标」收窄到「该类光标」，原版操作不再被吞。
 *
 * 使用示例：
 * <pre>
 * // 活TNT声明"可被活打火石右键点燃"（精确触发器，只拦活打火石）
 * new InteractionEntry(
 *     Items.TNT,              // 目标物品（我是谁）
 *     Items.FLINT_AND_STEEL,  // 触发物品（谁来交互）
 *     1,                      // 鼠标按键（右键）
 *     "ignite"                // 动作ID
 * )
 *
 * // 活按钮声明"右键自身按压"（真·任意光标自交互，无过滤）
 * new InteractionEntry(
 *     Items.STONE_BUTTON,     // 目标物品
 *     null,                   // 任意触发（自交互）
 *     1,                      // 右键
 *     "button_press"          // 动作ID
 * )
 *
 * // 活耕地声明"可被足量活种子右键种植"（通配 + 双物品过滤，只拦种子充足的光标）
 * new InteractionEntry(
 *     Items.FARMLAND,         // 目标物品
 *     null,                   // 通配（按类过滤而非单物品）
 *     1,                      // 右键
 *     "plant_crop",           // 动作ID
 *     PlantCropHandler::canPlantWith   // ← (trigger, target) 组合过滤
 * )
 * </pre>
 */
public record InteractionEntry(
    Item targetItem,
    @Nullable Item triggerItem,
    int button,
    String actionId,
    boolean onRelease,
    @Nullable BiPredicate<ItemStack, ItemStack> triggerFilter
) {
    public InteractionEntry(Item targetItem, @Nullable Item triggerItem, int button, String actionId) {
        this(targetItem, triggerItem, button, actionId, false, null);
    }

    public InteractionEntry(Item targetItem, @Nullable Item triggerItem, int button, String actionId,
                            boolean onRelease) {
        this(targetItem, triggerItem, button, actionId, onRelease, null);
    }

    /**
     * 判断给定的槽位物品是否匹配此交互的目标。
     * 要求物品类型匹配且为活物品。
     */
    public boolean matchesTarget(ItemStack stack) {
        return !stack.isEmpty() && stack.is(targetItem);
    }

    /**
     * 判断给定的光标物品是否匹配此交互的触发器（仅物品层）。
     *
     * <p>triggerItem=null 的通配条目恒真——组合级过滤（依赖 target 状态/数量等）
     * 走 {@code triggerFilter}（BiPredicate：trigger, target），
     * 由 {@link InteractionRegistry#findInteraction} 在两趟匹配中统一执行。</p>
     */
    public boolean matchesTrigger(ItemStack stack) {
        if (triggerItem == null) return true;
        return !stack.isEmpty() && stack.is(triggerItem);
    }
}
