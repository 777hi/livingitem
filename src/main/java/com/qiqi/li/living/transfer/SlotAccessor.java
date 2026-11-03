package com.qiqi.li.living.transfer;

import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 槽位访问器 —— 统一抽象不同存储后端的读写操作。
 *
 * <p>将传输引擎与具体存储类型解耦。传输引擎只调用 {@link #extract(int, ItemStack)}
 * 和 {@link #insert(ItemStack)}，不关心槽位背后是普通物品、活箱子、活末影箱还是跨容器。</p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>每个 Accessor 实例对应一个具体的槽位（可能跨容器）</li>
 *   <li>extract/insert 内部自行处理类型差异（直接槽位操作 / LivingChestFunction API / 跨容器代理）</li>
 *   <li>rollback 用于插入失败时退回物品</li>
 *   <li>isEmpty/isFull 用于前置判断，避免无效的 extract/insert 调用</li>
 * </ul>
 *
 * <h3>新增存储类型</h3>
 * <p>只需新增一个 Accessor 实现类，无需修改传输引擎代码。</p>
 */
public interface SlotAccessor {

    Logger ROLLBACK_LOGGER = LogUtils.getLogger();

    /**
     * 从槽位提取物品。
     *
     * @param amount 最大提取数量
     * @param filterType 过滤类型（null 表示不限类型），用于活箱子"预查+精确提取"策略
     * @return 提取的物品（可能为 EMPTY）
     */
    ItemStack extract(int amount, ItemStack filterType);

    /**
     * 向槽位插入物品。
     *
     * <p>调用后 {@code stack} 的 count 会被修改为剩余未插入的数量。</p>
     *
     * @param stack 要插入的物品（会被修改）
     * @return 实际插入的数量
     */
    int insert(ItemStack stack);

    /**
     * 模拟提取：检查源能提供多少物品，但不实际移除。
     *
     * <p>返回的 ItemStack 是副本，调用方可以安全地修改。
     * 不会修改源槽位的任何状态。</p>
     *
     * @param amount 最大提取数量
     * @return 模拟提取的物品副本（EMPTY 表示无法提取）
     */
    ItemStack simulateExtract(int amount);

    /**
     * 模拟插入：检查目标能接受多少物品，但不实际写入。
     *
     * <p>不会修改 {@code stack}，也不会修改目标槽位的任何状态。</p>
     *
     * @param stack 要检查的物品（不会被修改）
     * @return 可以接受的数量
     */
    int simulateInsert(ItemStack stack);

    /**
     * 回滚：将物品退回源槽位。
     *
     * <p>当 extract 成功但 insert 失败时调用，确保物品不丢失。
     * 在模拟优先模式下，此方法仅作为安全兜底，正常流程不应触发。</p>
     *
     * <p><strong>如果此方法被触发，说明 simulateInsert 的结果与真实 insert 不一致，
     * 属于模拟实现的 bug。</strong> {@link #transfer} 方法会在 rollback 时输出 WARN 日志，
     * 帮助定位是哪个 SlotAccessor 实现的模拟不准确。</p>
     *
     * @param stack 要退回的物品
     */
    void rollback(ItemStack stack);

    boolean isEmpty();

    boolean isFull();

    /**
     * 标记此槽位已被传输到达（用于级联防护）。
     */
    void markTransferred();

    /**
     * 同步此槽位到客户端。
     */
    void sync();

    /**
     * 解包装饰器，获取底层 SlotAccessor。
     *
     * <p>用于需要判断具体类型的场景（如 {@code instanceof LivingEnderChestAccessor}）。
     * 非装饰器实现返回 this，装饰器实现返回被装饰的对象。</p>
     *
     * @return 底层 SlotAccessor
     */
    default SlotAccessor unwrap() {
        return this;
    }

    /**
     * 统一的传输执行逻辑：模拟优先模式。
     *
     * <p>布尔入口保留给只关心“是否发生传输”的调用方；数量口径统一由
     * {@link #transferAmount(SlotAccessor, SlotAccessor, int)} 提供。</p>
     *
     * @param source 源槽位访问器
     * @param target 目标槽位访问器
     * @param amount 最大传输数量
     * @return 是否成功传输
     */
    static boolean transfer(SlotAccessor source, SlotAccessor target, int amount) {
        return transferAmount(source, target, amount) > 0;
    }

    /**
     * 统一的传输执行逻辑：模拟优先模式。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>模拟提取：检查源能提供多少物品（不实际移除）</li>
     *   <li>模拟插入：检查目标能接受多少物品（不实际写入）</li>
     *   <li>确认可行后，执行真实提取和插入</li>
     *   <li>rollback 仅作为安全兜底</li>
     * </ol>
     *
     * <p>返回值只认目标 {@link #insert(ItemStack)} 报告的真实插入量；模拟容量只决定
     * 本轮最多提取多少。目标少收时，未插入部分仍回滚到源槽。</p>
     *
     * @param source 源槽位访问器
     * @param target 目标槽位访问器
     * @param amount 最大传输数量
     * @return 目标实际插入数量（已限制在本轮真实提取量内）
     */
    static int transferAmount(SlotAccessor source, SlotAccessor target, int amount) {
        if (source.isEmpty() || target.isFull()) {
            return 0;
        }

        ItemStack simulated = source.simulateExtract(amount);
        if (simulated.isEmpty()) {
            return 0;
        }

        int canAccept = target.simulateInsert(simulated);
        if (canAccept <= 0) {
            return 0;
        }

        int toExtract = Math.min(canAccept, simulated.getCount());
        ItemStack extracted = source.extract(toExtract, null);
        if (extracted.isEmpty()) {
            return 0;
        }

        int before = extracted.getCount();
        int remainingBefore = before;
        int reportedInserted = target.insert(extracted);
        int remainingAfter = Math.max(0, Math.min(extracted.getCount(), remainingBefore));
        int actualInserted = remainingBefore - remainingAfter;
        // 目标契约的权威事实是传入栈被改成余量；返回值只作违约诊断，不能拿谎报量扣源。
        if (reportedInserted != actualInserted) {
            ROLLBACK_LOGGER.warn("SlotAccessor insert report mismatch: reported={} actual={} (source={}, target={}, item={})",
                reportedInserted, actualInserted, source.getClass().getSimpleName(), target.getClass().getSimpleName(),
                extracted.getItem());
        }
        int inserted = actualInserted;
        if (inserted <= 0) {
            ROLLBACK_LOGGER.warn("SlotAccessor rollback: insert returned {} after simulateInsert said {} (source={}, target={}, item={})",
                reportedInserted, canAccept, source.getClass().getSimpleName(), target.getClass().getSimpleName(),
                extracted.getItem());
            source.rollback(extracted.copyWithCount(before));
            return 0;
        }

        int remainderCount = remainingAfter;
        if (remainderCount > 0) {
            ItemStack leftover = extracted.copyWithCount(remainderCount);
            ROLLBACK_LOGGER.warn("SlotAccessor rollback: partial insert simulated={} actual={} leftover={} (source={}, target={}, item={})",
                canAccept, reportedInserted, remainderCount, source.getClass().getSimpleName(), target.getClass().getSimpleName(),
                extracted.getItem());
            source.rollback(leftover);
        }

        target.markTransferred();
        source.sync();
        target.sync();
        return inserted;
    }
}