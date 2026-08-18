package com.qiqi.li.living.api;

import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

/**
 * 活物品功能接口 —— 定义活物品的行为契约。
 *
 * <h3>职责拆分（逻辑分离，物理统一）</h3>
 * <ul>
 *   <li><b>匹配</b>：{@link #canApply(ItemStack)} —— 判断功能是否能应用到物品</li>
 *   <li><b>标识</b>：{@link #getFunctionId()} —— 唯一标识功能类型</li>
 *   <li><b>Tick</b>：{@link #tick(List, ContainerContext, TickContext, Level)} —— 每 tick 执行的功能逻辑</li>
 *   <li><b>Tooltip</b>：{@link #addToTooltip(...)} —— 添加到物品提示信息（可选）</li>
 *   <li><b>组件过滤</b>：{@link #getIgnoredComponentTypes()} —— 比较时忽略的组件类型（可选）</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <p>所有方法统一在此接口中定义，但职责逻辑上分离。
 * 可选方法提供默认空实现，实现类只需覆盖需要的方法。</p>
 */
public interface LivingItemFunction {

    /**
     * 槽位条目记录。
     */
    record SlotEntry(int slotIndex, ItemStack stack) {}

    /**
     * 【匹配】判断功能是否能应用到物品。
     *
     * @param stack 待检查的物品
     * @return true 表示此功能适用于该物品
     */
    boolean canApply(ItemStack stack);

    /**
     * 【标识】获取功能的唯一标识符。
     *
     * @return 功能 ID（如 "living_chest"、"living_hopper"）
     */
    String getFunctionId();

    /**
     * 【Tick】执行功能 tick 逻辑。
     *
     * @param entries 拥有此功能的活物品列表（槽位+物品）
     * @param container 容器上下文（提供容器能力）
     * @param tick Tick 级上下文（提供临时状态）
     * @param level 世界
     */
    void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level);

    /**
     * 【Tooltip】添加到物品提示信息（可选）。
     *
     * <p>默认空实现，需要显示 tooltip 的功能覆盖此方法。</p>
     *
     * @param context tooltip 上下文
     * @param tooltipAdder tooltip 添加器
     * @param flag tooltip 标志
     * @param stack 物品
     */
    default void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                              java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                              net.minecraft.world.item.TooltipFlag flag,
                              ItemStack stack) {
    }

    /**
     * 【组件过滤】获取比较时应忽略的组件类型（可选）。
     *
     * <p>默认返回空集合，需要忽略某些组件的功能覆盖此方法。</p>
     *
     * @return 应忽略的组件类型集合
     */
    default Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    /**
     * 【比较器输出】获取活比较器检测此物品时的输出值（可选）。
     *
     * <p>默认返回 0，表示活比较器不检测此活物品。
     * 覆盖此方法可以自定义检测值（如熔炉进度、水桶水量等）。</p>
     *
     * @param stack 物品
     * @return 比较器输出值（0-15 范围），0 表示不检测
     */
    default int getComparatorOutput(ItemStack stack) {
        return 0;
    }
}