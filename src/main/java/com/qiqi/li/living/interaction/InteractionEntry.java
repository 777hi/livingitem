package com.qiqi.li.living.interaction;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.function.BiPredicate;
import javax.annotation.Nullable;

/**
 * GUI交互规则条目 —— 声明一条"光标物品A + 按键 → 槽位物品B → 动作"的交互规则。
 *
 * <p>规则由 {@code InteractionRuleConfig} 从 JSON 加载（内置资源 + 玩家差异，D2），
 * 由 InteractionRegistry 统一管理查询；动作执行体（handler）仍是 Java 代码，
 * 按 {@code actionId} 关联 —— <b>规则是数据、行为是代码</b>，JSON 只能把已有动作
 * 接到新的物品组合上，不能创造新行为。</p>
 *
 * 匹配流程：
 *   客户端检测到鼠标按键 → 查询 InteractionRegistry →
 *   匹配 trigger + target + button → 发送 GuiInteractionPacket(actionId) →
 *   服务端通过 actionId 查找 InteractionHandler 执行逻辑
 *
 * 自交互（self-interaction）：
 *   当 trigger 两项均为 null 时，表示目标物品可以被任意光标物品（包括空手）右键触发。
 *   适用于按钮按压、拉杆切换等自激活场景。
 *
 * ⚠️ 通配条目的拦截代价（2026-09-13 实测反馈）：
 *   通配条目会让客户端拦截「所有」右键——包括原版的拿起/放置/
 *   分堆等操作（客户端拦截发生在原版逻辑前，不匹配时 handler 静默返回，但原版
 *   点击已被取消，右键分堆等操作被吞）。若通配条目的实际语义是「某一类光标可触发」
 *   （如种植：任意可种植种子），<b>必须</b>提供 {@code triggerFilter} 把可触发光标
 *   收窄到该类物品——拦截面从「任意光标」收窄到「该类光标」，原版操作不再被吞。
 *
 * <h3>tag 支持（D2，2026-09-28）</h3>
 * target / trigger 均可为物品 ID 或 {@link TagKey}（二选一，互斥）。
 * 典型收益：13 种按钮逐条注册 → {@code #minecraft:buttons} 一条。
 * 存 TagKey 而非展开物品集合 —— commonSetup 时 tag 尚未 resolve，
 * 匹配期（进世界后）{@code stack.is(TagKey)} 才查询，天然免掉时序问题。
 */
public record InteractionEntry(
    @Nullable Item targetItem,
    @Nullable TagKey<Item> targetTag,
    @Nullable Item triggerItem,
    @Nullable TagKey<Item> triggerTag,
    int button,
    String actionId,
    boolean onRelease,
    @Nullable BiPredicate<ItemStack, ItemStack> triggerFilter
) {
    /**
     * 规范化：target / trigger 各自必须「ID 与 tag 二选一」——都空 = 无目标，
     * 都非空 = 配置自相矛盾。加载器与便捷构造器最终都走这里，坏配置在此拦下。
     */
    public InteractionEntry {
        if (targetItem == null && targetTag == null) {
            throw new IllegalArgumentException("InteractionEntry[" + actionId + "]: target 缺失（item / tag 须二选一）");
        }
        if (targetItem != null && targetTag != null) {
            throw new IllegalArgumentException("InteractionEntry[" + actionId + "]: target 的 item 与 tag 只能填一个");
        }
    }

    // ── 便捷构造器（旧形态：Item target；Java 侧少量调用与测试用） ──

    public InteractionEntry(Item targetItem, @Nullable Item triggerItem, int button, String actionId) {
        this(targetItem, null, triggerItem, null, button, actionId, false, null);
    }

    public InteractionEntry(Item targetItem, @Nullable Item triggerItem, int button, String actionId,
                            boolean onRelease) {
        this(targetItem, null, triggerItem, null, button, actionId, onRelease, null);
    }

    public InteractionEntry(Item targetItem, @Nullable Item triggerItem, int button, String actionId,
                            boolean onRelease, @Nullable BiPredicate<ItemStack, ItemStack> triggerFilter) {
        this(targetItem, null, triggerItem, null, button, actionId, onRelease, triggerFilter);
    }

    /**
     * 判断给定的槽位物品是否匹配此交互的目标（物品 ID 或 tag）。
     */
    public boolean matchesTarget(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return targetItem != null ? stack.is(targetItem) : stack.is(targetTag);
    }

    /**
     * 判断给定的光标物品是否匹配此交互的触发器（仅物品/tag 层）。
     *
     * <p>两者皆 null 的通配条目恒真——组合级过滤（依赖 target 状态/数量等）
     * 走 {@code triggerFilter}（BiPredicate：trigger, target），
     * 由 {@link InteractionRegistry#findInteraction} 在两趟匹配中统一执行。</p>
     */
    public boolean matchesTrigger(ItemStack stack) {
        if (triggerItem == null && triggerTag == null) return true;
        if (stack.isEmpty()) return false;
        return triggerItem != null ? stack.is(triggerItem) : stack.is(triggerTag);
    }

    /** target 侧用的是 tag 吗（描述规则时区分粒度用）。 */
    public boolean hasTargetTag() {
        return targetTag != null;
    }
}
