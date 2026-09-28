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
 *   <li><b>Tick 顺序</b>：{@link #getTickPriority()} —— 多个功能共存时的执行次序（可选）</li>
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
     * 【Tick 顺序】本功能在容器 tick 中的执行优先级（可选）。
     *
     * <p><b>数值小的先执行；优先级相同时保持注册顺序</b>（排序是稳定的）。
     * 默认 {@code 0} ⇒ 不覆盖时，整体顺序<b>与今天完全一致</b>（即注册顺序）。</p>
     *
     * <p>⚠️ <b>为什么需要它</b>：A1 把注册按域下放成 11 个分散文件后，
     * tick 顺序变成了「主类调用次序 × 各文件内部排列」——<b>从此没有任何一处能读出
     * 完整顺序</b>，也看不出哪些相邻是有意的、哪些只是巧合。
     * 本方法把这条隐式契约变成<b>可声明、可 grep、可断言</b>的显式契约。</p>
     *
     * <p>注：{@code HasContainerData#getPriority()} 已经把容器级数据的顺序显式化过一次，
     * 说明「顺序需要显式声明」这件事项目里已经认过 —— 本方法是同一类改造，只是作用于
     * {@code tick()} 本身。</p>
     *
     * @return 优先级，默认 0
     */
    default int getTickPriority() {
        return 0;
    }

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
     * 【数据归属】本功能挂载在物品上的 DataComponent（取消活化时由框架统一清除）。
     *
     * <p><b>为什么需要</b>：原先由 {@code LivingItemManager.clearLivingData} 集中维护一份
     * remove 清单，导致「新增 {@link LivingItemFunction} 必须去改核心类的那个方法」——
     * 第三方因此只能 fork 本仓库。改为各功能<b>自声明</b>后，第三方无需触碰本模组的任何文件。
     *
     * <p>⚠️ <b>必须列全部</b>：漏列的组件会在物品取消活化后<b>变成孤儿数据</b>
     * （组件还在、但没有功能认领它），表现为物品描述异常或再次活化时读到脏旧值。
     *
     * <p>与 {@link #getIgnoredComponentTypes()} 的区别：
     * <ul>
     *   <li>{@code getOwnedComponentTypes} —— 「这是我的」，激活/失效时随之增删</li>
     *   <li>{@code getIgnoredComponentTypes} —— 「比较时别看我」，用于可堆叠性判定</li>
     * </ul>
     * 二者语义正交，同一组件可以同时出现在两边（如 {@code LIVING_FURNACE_BURNING}）。
     *
     * @return 本功能拥有的 DataComponent 类型；默认空集合（无自有数据的功能不必覆盖）
     */
    default Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of();
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