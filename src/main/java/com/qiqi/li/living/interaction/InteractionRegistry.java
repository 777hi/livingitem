package com.qiqi.li.living.interaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.world.item.ItemStack;

/**
 * GUI交互注册表 —— 全局统一管理活物品的GUI交互规则和处理器。
 *
 * 两部分数据：
 *   1. 交互规则（InteractionEntry）：声明"什么物品 + 什么按键 → 什么动作"
 *   2. 交互处理器（InteractionHandler）：服务端执行具体逻辑
 *
 * 注册时机：
 *   - 交互规则：由 LivingFunctionConfig.addInteraction() 在功能类静态初始化时注册
 *   - 交互处理器：由模组初始化时注册（如 LivingItem.onRegisterPayloadHandler）
 *
 * 查询流程：
 *   光标物品(trigger) + 槽位物品(target) + 按键(button)
 *   → 遍历所有注册的 InteractionEntry
 *   → 匹配 triggerItem + targetItem + button
 *   → 同时验证双方都是活物品
 *   → 返回匹配的 InteractionEntry
 */
public class InteractionRegistry {

    private static final List<InteractionEntry> ENTRIES = new ArrayList<>();
    private static final Map<String, InteractionHandler> HANDLERS = new HashMap<>();

    /**
     * 注册一条交互规则。
     * 通常由 LivingFunctionConfig.addInteraction() 内部调用。
     */
    public static void register(InteractionEntry entry) {
        ENTRIES.add(entry);
    }

    /**
     * 注册交互处理器。
     * actionId 必须与 InteractionEntry 中的 actionId 对应。
     *
     * @param actionId 动作ID（如 "ignite"）
     * @param handler  处理器实现
     */
    public static void registerHandler(String actionId, InteractionHandler handler) {
        HANDLERS.put(actionId, handler);
    }

    /**
     * 获取交互处理器。
     *
     * @param actionId 动作ID
     * @return 对应的处理器，不存在返回 null
     */
    public static InteractionHandler getHandler(String actionId) {
        return HANDLERS.get(actionId);
    }

    /**
     * 查询匹配的交互规则。
     *
     * <p><b>两趟优先级匹配</b>（防「通配遮蔽精确」）：同一目标物品可能同时挂
     * 通配条目（triggerItem=null，任意光标触发）与精确条目（triggerItem 指定物品）。
     * 典型事故：活耕地的 plant_crop（通配，任意活种子可种）与 bonemeal（精确，
     * 活骨粉催熟）同 target/button 注册——若按注册顺序首配，骨粉永远命中先注册的
     * plant_crop，bonemeal 成为死代码（2026-09-13 游戏实测踩坑）。
     * 第一趟只找精确条目，第二趟才找通配条目，保证精确触发永不被通配截胡。</p>
     *
     * @param trigger 光标持有的物品（触发方）
     * @param target  悬浮槽位的物品（目标方）
     * @param button  鼠标按键（0=左键, 1=右键, 2=中键）
     * @return 匹配的交互条目，无匹配返回 null
     */
    public static InteractionEntry findInteraction(ItemStack trigger, ItemStack target, int button, boolean onRelease) {
        InteractionEntry wildcard = null;
        for (InteractionEntry entry : ENTRIES) {
            if (entry.button() != button) continue;
            if (entry.onRelease() != onRelease) continue;
            if (!entry.matchesTarget(target)) continue;
            if (!LivingItemManager.isLivingItem(target)) continue;
            if (!entry.matchesTrigger(trigger)) continue;
            if (entry.triggerItem() == null) {
                if (wildcard == null && passesFilter(entry, trigger, target)) {
                    wildcard = entry;   // 通配条目记住首个，让位于精确条目
                }
                continue;
            }
            if (!LivingItemManager.isLivingItem(trigger)) continue;
            if (!passesFilter(entry, trigger, target)) continue;
            return entry;   // 精确条目立即返回
        }
        return wildcard;
    }

    /** 组合级过滤（triggerFilter：trigger, target）——数量/状态类门槛在匹配阶段即拒绝 */
    private static boolean passesFilter(InteractionEntry entry, ItemStack trigger, ItemStack target) {
        return entry.triggerFilter() == null || entry.triggerFilter().test(trigger, target);
    }

    /**
     * 获取所有已注册的交互规则（用于调试）。
     */
    public static List<InteractionEntry> getEntries() {
        return List.copyOf(ENTRIES);
    }

    /**
     * 清空全部注册（条目 + 处理器）。仅供单元测试隔离使用——
     * 生产注册发生在 commonSetup，游戏运行期不调用。
     */
    static void clearForTest() {
        ENTRIES.clear();
        HANDLERS.clear();
    }
}