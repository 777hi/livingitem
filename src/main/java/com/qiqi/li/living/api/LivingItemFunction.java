package com.qiqi.li.living.api;

import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Player;
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

    /**
     * 【自维持】本功能是否能在「容器内<b>没有它自己的活物品</b>」时仍然执行 tick。
     *
     * <p>默认 {@code false}：绝大多数功能需要容器里实际存在该活物品才 tick
     * （如活熔炉需要熔炉物品、活漏斗需要漏斗物品）。活水源是例外——
     * 它是一种「没有具体物品的活物品」，即使容器里没有任何活物品，也要持续更新
     * 其容器级状态（水势、连通性、溢出判定等）。</p>
     *
     * <p>⚠️ <b>性能约定（务必遵守）</b>：本方法在<b>注册期</b>由
     * {@link com.qiqi.li.living.api.LivingItemManager} 以 {@code null} 上下文调用一次，
     * 用于构建「自维持函数清单」。每 tick 的 {@code processContext} 只遍历这张<b>静态清单</b>
     * （通常 0~1 个），<b>不会</b>逐函数调用本方法——因此覆盖时请把它当作
     * <b>静态声明</b>：忽略 {@code ctx} 参数（或容忍 null），返回恒定真值。
     * 真正的「是否这次真的要工作」留给 {@code tick(emptyList, ...)} 内部按容器状态判断。</p>
     *
     * @param ctx 容器上下文（注册期可能为 null，覆盖时请勿依赖）
     * @return true 表示即使没有自身活物品也参与 tick
     */
    default boolean shouldTickWithoutOwnItems(@Nullable ContainerContext ctx) {
        return false;
    }

    // ==================================================================
    // 活化时机钩子（2026-10-04 收编，见 docs/buffer/activation-hook-refactoring-plan.md §3）
    //
    // ⭐ 为什么是「收编」而不是「新增 hook」：这五段「活化这一刻要挂什么」原先散落在
    //    LivingTagPacket 里（内联类型判断）。与 A2 的 getOwnedComponentTypes() 同构 ——
    //    同一件事的两半：组件归属自声明 + 活化时机自声明。
    // ==================================================================

    /**
     * 【活化时机】此功能认领的物品<b>被活化</b>的那一刻（可选，默认空实现）。
     *
     * <p><b>派发给「认领该物品的功能」</b>（{@code getApplicableFunctions}），
     * 由 {@link LivingItemActivation#apply} 在 {@code IS_LIVING} 写入<b>之后</b>调用。</p>
     *
     * <h3>不变量（违反即 bug）</h3>
     * <ol>
     *   <li>⭐ <b>调用时物品必定处于「活」状态</b> —— 因为本方法靠
     *       {@code canApply} 派发，而绝大多数 {@code canApply} 内部含
     *       {@code isLivingItem(stack)}。这让两侧都能直接用既有判据，
     *       <b>不需要 copy 探针</b>。</li>
     *   <li>只在<b>服务端</b>调用。</li>
     * </ol>
     *
     * <h3>关于 {@code player} 可为 null ⭐</h3>
     * <p><b>「玩家缺席」不是错误状态</b>：本项目既有的传统里，无主活工具走
     * {@code FALLBACK_UUID} 兜底、未绑定活末影箱走路由模式（公共黑板）——
     * 二者都是<b>已存在的合法模式</b>。
     * 逐功能的缺席行为写在该功能自己的实现里（属下游），框架不代为规定。</p>
     *
     * <p>⚠️ <b>本方法不得依赖 {@code player} 非空</b>；确实需要玩家数据时，
     * 降级行为必须显式写出（典型：{@code if (player == null) return;}）。</p>
     *
     * <h3>触及邻接的通道</h3>
     * <p>持续影响 → {@link #tick}（自带 {@code ContainerContext}）；
     * 玩家显式触发的一次性操作 → 已有的 {@code InteractionHandler} 通道。
     * <b>只有「一次性 + 槽位上下文」确实无处安放时</b>才走
     * {@link LivingItemActivation#apply} 的门面参数，而不是把上下文塞进本方法。</p>
     *
     * @param stack 刚被活化、<b>已带 IS_LIVING</b> 的物品（直接改它即写入了组件）
     * @param level 世界（<b>必填</b> —— 三个活化入口天然都有；箱子的掉落位置等只需它 + 一个可选玩家）
     * @param player 发起者；<b>可为 null</b>（内部产出 / 批量转化场景）
     * @param via 发起途径（判定面已按途径分流；本参数供功能按来源区分行为）
     */
    default void onActivated(ItemStack stack, Level level, @Nullable Player player,
            LivingItemActivation.Via via) {
    }

    /**
     * 【活化时机】此功能认领的物品<b>被取消活化</b>的那一刻（可选，默认空实现）。
     *
     * <p>由 {@link LivingItemActivation#apply} 在 {@code clearLivingData()} <b>之前</b>调用
     * —— 顺序是硬约束：判据（各功能 {@code canApply}）都含 {@code isLivingItem(stack)}，
     * {@code IS_LIVING} 被清之后它们恒为 false ⇒ 功能认不出物品。</p>
     *
     * <p>⭐ <b>返回值 = 下游自决的否决通道</b>：返回 {@code false} 表示
     * 「本次不做这个动作」，框架将<b>保持物品的活状态、不清任何数据</b>，
     * 并如实向调用方报告「状态未被切换」。</p>
     *
     * <p><b>上游只承诺「否决 ⇒ 零改动」这一件事</b>；
     * <b>为什么否决、数据本来是否安全，全由本功能自己判断</b>，框架无从知晓。
     * 典型场景是「本功能需要的位置/上下文此刻拿不到」——那就拒绝，不要硬来。</p>
     *
     * <p>⚠️ 约定：<b>返回 {@code false} 的路径不得已产生副作用</b>
     * （框架会把所有认领功能都问一遍，任一否决即整体中止）。</p>
     *
     * @param stack 即将被取消活化、<b>仍带 IS_LIVING</b> 的物品
     * @param level 世界（必填）
     * @param player 发起者；<b>可为 null</b> ⇒ 本方法若需要玩家数据，必须自行定义降级或拒绝
     * @param via 发起途径
     * @return true（默认）继续取消活化；false = 本次不做，框架不清数据
     */
    default boolean onDeactivated(ItemStack stack, Level level, @Nullable Player player,
            LivingItemActivation.Via via) {
        return true;
    }
}