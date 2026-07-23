package com.qiqi.li.living.chest;

import java.util.List;
import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * 活箱子堆叠上下文标志 —— 在玩家 GUI 操作期间标记允许跨 UUID 堆叠。
 * 由 AbstractContainerMenuMixin 在 clicked() 前后设置/清除，
 * 由 ItemStackMixin 在 isSameItemSameComponents 中检查。
 *
 * <h3>方案A：ALLOW_STACK 仅用于堆叠判定</h3>
 * <p>ALLOW_STACK 的唯一作用是让 isSameItemSameComponents 忽略 UUID 差异，
 * 允许不同 UUID 的活箱子在玩家 GUI 操作时相互堆叠。
 * split/shrink/grow/setCount/copyWithCount 中的 UUID 转移逻辑不再检查 ALLOW_STACK，
 * 确保漏斗、投掷器、模组管道等自动化系统也能正确处理 UUID 的合并与拆分。</p>
 *
 * <h3>ThreadLocal 变量完整清单</h3>
 * <table>
 *   <tr><th>变量</th><th>用途</th><th>设置者</th><th>消费者</th></tr>
 *   <tr><td>{@link #ALLOW_STACK}</td><td>标记玩家 GUI 操作（仅用于堆叠判定）</td>
 *       <td>AbstractContainerMenuMixin</td>
 *       <td>isSameItemSameComponents</td></tr>
 *   <tr><td>{@link #IS_QUICK_CRAFT}</td><td>标记 QUICK_CRAFT 操作</td>
 *       <td>AbstractContainerMenuMixin</td>
 *       <td>onCopyWithCount</td></tr>
 *   <tr><td>{@link #BLOCK_PLACING_UUIDS}</td><td>方块放置时暂存 UUID</td>
 *       <td>BlockItemMixin</td>
 *       <td>onShrink, onSetCount</td></tr>
 *   <tr><td>{@link #PRE_SPLIT_UUIDS}</td><td>split 原始 UUID 快照 + 互斥标志</td>
 *       <td>onSplitHead</td>
 *       <td>onCopyWithCount, onShrink, onSetCount, onGrow</td></tr>
 *   <tr><td>{@link #SPLIT_UUIDS_FOR_GROW}</td><td>split 后 grow 的 UUID</td>
 *       <td>onSplitReturn</td>
 *       <td>onGrow</td></tr>
 *   <tr><td>{@link #PENDING_TRANSFER}</td><td>grow/shrink/setCount 间 UUID 中转</td>
 *       <td>onGrow, onShrink, onSetCount</td>
 *       <td>onGrow, onShrink, onSetCount</td></tr>
 * </table>
 */
public final class LivingChestStackFlags {

    /** 玩家 GUI 堆叠标志：仅在玩家鼠标/键盘操作时允许活箱子跨 UUID 堆叠。
     *  方案A：此标志仅用于 isSameItemSameComponents 的堆叠判定，
     *  不再影响 split/shrink/grow/setCount/copyWithCount 的 UUID 转移逻辑。 */
    public static final ThreadLocal<Boolean> ALLOW_STACK = new ThreadLocal<>();

    /**
     * QUICK_CRAFT 标志：标识当前点击操作为右键拖动分发（ClickType.QUICK_CRAFT）。
     * 用于在 copyWithCount 中区分创造模式下的正常拆分（QUICK_CRAFT）和复制（CLONE），
     * 避免误清空 UUID。
     */
    public static final ThreadLocal<Boolean> IS_QUICK_CRAFT = new ThreadLocal<>();

    /**
     * 方块放置标志：当活箱子正在被放置为方块时，存储捕获的 UUID 列表。
     * 非 null 时表示正在放置方块，onShrink/onSetCount 会跳过处理。
     * HEAD 注入中设置，RETURN 注入中消费并清除。
     */
    public static final ThreadLocal<List<UUID>> BLOCK_PLACING_UUIDS = new ThreadLocal<>();

    /**
     * split() 前捕获的原始 UUID 快照。
     * 同时作为互斥标志：非 null 时 onCopyWithCount/onShrink/onSetCount/onGrow 会跳过处理。
     * 在 onSplitHead 中设置，在 onSplitReturn 中 remove() 清理。
     */
    public static final ThreadLocal<List<UUID>> PRE_SPLIT_UUIDS = new ThreadLocal<>();

    /**
     * split 产出的新堆 UUID，供紧随其后的 grow() 合并使用。
     * 在 onSplitReturn 中设置，在 onGrow 中消费并移除。
     * 与 PENDING_TRANSFER 的区别：这个是专门给 split 后的 grow 用的，
     * 不会被 PENDING_TRANSFER 的通用逻辑干扰。
     */
    public static final ThreadLocal<List<UUID>> SPLIT_UUIDS_FOR_GROW = new ThreadLocal<>();

    /**
     * 线程局部存储，在 grow/shrink/setCount 之间传递待转移的 UUID 数据。
     * 每次操作完成后必须 remove() 清理，避免泄漏到无关操作。
     * 支持三种数据来源：grow-first、shrink-first、setCount 源堆清零。
     */
    public static final ThreadLocal<MergeTransfer> PENDING_TRANSFER = new ThreadLocal<>();

    /**
     * UUID 合并传输数据记录。
     *
     * @param target 目标 ItemStack（grow-first 时记录目标堆引用，shrink-first 时为 null）
     * @param amount 预期的传输数量
     * @param uuids  待转移的 UUID 列表，null 表示 grow-first 等待 shrink 来填充
     */
    public record MergeTransfer(ItemStack target, int amount, List<UUID> uuids) {}

    private LivingChestStackFlags() {}
}